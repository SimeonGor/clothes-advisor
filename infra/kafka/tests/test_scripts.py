"""ADDED scenarios for KAFKA-02-v1; no previous Kafka scripts existed."""
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest


KAFKA = Path(__file__).resolve().parents[1]


def run(*args, env=None):
    return subprocess.run(args, text=True, capture_output=True, env=env)


class PreparationCliTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workspace = tempfile.TemporaryDirectory()
        cls.base = Path(cls.workspace.name)
        cls.credentials = cls.base / "credentials"
        cls.first = run(sys.executable, str(KAFKA / "prepare.py"), "--secrets-dir", str(cls.credentials))

    @classmethod
    def tearDownClass(cls):
        cls.workspace.cleanup()

    def copy_credentials(self, name):
        target = self.base / name
        shutil.copytree(self.credentials, target)
        return target

    def prepare(self, path):
        return run(sys.executable, str(KAFKA / "prepare.py"), "--secrets-dir", str(path))

    def test_first_preparation_private_scoped_credentials_and_distinct_identities(self):
        # Given a new absolute directory outside the repository.
        # When setUpClass prepares the shared credentials through the CLI.
        self.assertEqual(self.first.returncode, 0, self.first.stderr)

        # Then credentials are private at the host boundary and readable in scoped mounts.
        self.assertEqual(stat.S_IMODE(self.credentials.stat().st_mode), 0o700)
        self.assertEqual(stat.S_IMODE((self.credentials / "private/ca.key").stat().st_mode), 0o600)
        passwords, keys, clusters = set(), set(), set()
        for principal in ("bootstrap-admin", "account-service", "wardrobe-service",
                          "recommendation-service", "notification-service"):
            directory = self.credentials / principal
            self.assertEqual(stat.S_IMODE(directory.stat().st_mode), 0o755)
            self.assertEqual(list(p.name for p in directory.iterdir()), ["client.properties"])
            client = (directory / "client.properties").read_text()
            self.assertIn('username="' + principal + '"', client)
            passwords.add(client.split('password="')[1].split('"')[0])
        self.assertEqual(len(passwords), 5)
        for node in range(1, 4):
            directory = self.credentials / f"kafka-{node}"
            self.assertEqual(stat.S_IMODE(directory.stat().st_mode), 0o755)
            self.assertEqual(stat.S_IMODE((directory / "key.pem").stat().st_mode), 0o444)
            keys.add((directory / "key.pem").read_text())
            clusters.add((directory / "cluster.id").read_text())
            self.assertFalse((directory / "ca.key").exists())
            verified = run("openssl", "verify", "-CAfile", str(self.credentials / "private/ca.pem"),
                           str(directory / "certificate.pem"))
            self.assertEqual(verified.returncode, 0, verified.stderr)
            for password in passwords:
                self.assertNotIn(password, (directory / "scram.txt").read_text())
                self.assertNotIn(password, self.first.stdout + self.first.stderr)
        self.assertEqual(len(keys), 3)
        self.assertEqual(len(clusters), 1)

    def test_repeat_does_not_modify_any_file(self):
        # Given complete credentials.
        before = {str(p): (p.read_bytes(), p.stat().st_mtime_ns)
                  for p in self.credentials.rglob("*") if p.is_file()}

        # When preparation repeats.
        repeated = self.prepare(self.credentials)

        # Then even mtimes stay fixed.
        self.assertEqual(repeated.returncode, 0, repeated.stderr)
        after = {str(p): (p.read_bytes(), p.stat().st_mtime_ns)
                 for p in self.credentials.rglob("*") if p.is_file()}
        self.assertEqual(before, after)

    def test_partial_state_refused_without_deletion(self):
        # Given an interrupted preparation.
        directory = self.base / "partial"
        directory.mkdir(mode=0o700)
        marker = directory / "keep"
        marker.write_text("existing-data")

        # When preparation is retried.
        result = self.prepare(directory)

        # Then its existing content survives.
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(marker.read_text(), "existing-data")
        self.assertEqual(len(list(directory.iterdir())), 1)

    def test_config_mismatch_and_tampering_refused(self):
        for kind in ("config", "key"):
            with self.subTest(kind=kind):
                # Given mismatched metadata or a changed key.
                directory = self.copy_credentials(kind)
                if kind == "config":
                    path = directory / "manifest.json"
                    content = json.loads(path.read_text())
                    content["config"]["version"] = 99
                    path.write_text(json.dumps(content))
                else:
                    path = directory / "kafka-1/key.pem"
                    path.chmod(0o600)
                    path.write_text("changed-key")
                before = path.read_bytes()

                # When preparation is retried.
                result = self.prepare(directory)

                # Then no repair or rotation occurs.
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(before, path.read_bytes())

    def test_relative_repository_symlink_and_nonprivate_paths_rejected(self):
        # Given unsafe destinations.
        link = self.base / "link"
        link.symlink_to(self.credentials)
        nonprivate = self.base / "nonprivate"
        nonprivate.mkdir(mode=0o755)
        for path in (Path("relative"), KAFKA / "forbidden-credentials", link, nonprivate):
            with self.subTest(path=path):
                # When preparation is called.
                result = self.prepare(path)

                # Then the destination is rejected.
                self.assertNotEqual(result.returncode, 0)
        self.assertFalse((KAFKA / "forbidden-credentials").exists())


class KafkaShellCliTest(unittest.TestCase):
    def setUp(self):
        self.workspace = tempfile.TemporaryDirectory()
        self.addCleanup(self.workspace.cleanup)
        self.base = Path(self.workspace.name)
        (self.base / "bin").mkdir()
        for name in ("kafka-storage.sh", "kafka-server-start.sh", "kafka-topics.sh",
                     "kafka-acls.sh", "kafka-broker-api-versions.sh"):
            path = self.base / "bin" / name
            shutil.copyfile(KAFKA / "tests/fixtures/kafka_cli.py", path)
            path.chmod(0o755)
        self.state = self.base / "state.json"
        self.calls = self.base / "calls.jsonl"
        self.env = dict(os.environ, KAFKA_HOME=str(self.base), FAKE_STATE=str(self.state),
                        FAKE_CALLS=str(self.calls))
        self.credentials = self.base / "credentials"
        self.credentials.mkdir()
        for name, content in {"node.id": "1\n", "cluster.id": "A" * 22 + "\n",
                              "scram.txt": "SCRAM-SHA-512=[name=fixture,saltedpassword=fixture]\n",
                              "server.properties": "node.id=1\n"}.items():
            (self.credentials / name).write_text(content)
        self.data = self.base / "data"

    def start(self):
        return run("bash", str(KAFKA / "start.sh"), str(self.credentials), str(self.data), "1", env=self.env)

    def provision(self):
        return run("bash", str(KAFKA / "provision.sh"), "/fixture/admin.properties", env=self.env)

    def test_storage_first_start_repeat_and_diagnostics(self):
        # Given storage initialized from an empty directory.
        first = self.start()
        self.assertEqual(first.returncode, 0, first.stderr)
        (self.data / "message").write_text("retained")

        # When the node starts again.
        repeated = self.start()

        # Then format ran once and data survives.
        self.assertEqual(repeated.returncode, 0, repeated.stderr)
        calls = [json.loads(line) for line in self.calls.read_text().splitlines()]
        self.assertEqual(sum(call[0] == "kafka-storage.sh" for call in calls), 1)
        self.assertEqual((self.data / "message").read_text(), "retained")
        self.assertIn("Kafka server fixture started", first.stdout)
        self.assertFalse((self.data / "server.log").exists())

    def test_storage_mismatch_and_partial_refused_without_format(self):
        # Given incompatible or partial persistent state.
        self.data.mkdir()
        marker = self.data / "message"
        marker.write_text("retained")
        meta = self.data / "meta.properties"
        for metadata in (None, "cluster.id=wrong\nnode.id=1\n", "cluster.id=" + "A" * 22 + "\nnode.id=2\n"):
            with self.subTest(metadata=metadata):
                if metadata:
                    meta.write_text(metadata)

                # When the node starts.
                result = self.start()

                # Then nothing is reformatted.
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(self.calls.exists())
                self.assertEqual(marker.read_text(), "retained")

    def test_topology_repeat_exact_topics_groups_and_no_deletion(self):
        # Given a cluster provisioned from an empty state.
        first = self.provision()
        self.assertEqual(first.returncode, 0, first.stderr)
        before = self.state.read_bytes()

        # When provisioning repeats.
        second = self.provision()

        # Then its logical ACL and topic set is stable.
        self.assertEqual(second.returncode, 0, second.stderr)
        self.assertEqual(before, self.state.read_bytes())
        state = json.loads(before)
        self.assertEqual(set(state["topics"]), {"stylist-access.events", "stylist-requests.events",
                         "stylist-outfits.events", "outfit-ratings.events", "ai-jobs.events",
                         "ai-selection.requests", "ai-selection.execution-events"})
        groups = {acl[acl.index("--group") + 1] for acl in state["acls"] if "--group" in acl}
        self.assertEqual(groups, {"notification-service.events", "recommendation-service.ai-jobs",
                                 "recommendation-service.ai-selection", "wardrobe-service.ai-selection"})
        calls = [json.loads(line) for line in self.calls.read_text().splitlines()]
        for call in calls:
            self.assertFalse({"--delete", "--remove", "--alter", "--producer", "*"} & set(call))

    def test_topology_mismatch_refuses_destructive_repair(self):
        # Given existing topics.
        self.assertEqual(self.provision().returncode, 0)
        original = json.loads(self.state.read_text())
        for field, invalid in (("partitions", 1), ("replicas", 1), ("config", ["min.insync.replicas=1"])):
            state = json.loads(json.dumps(original))
            state["topics"]["ai-jobs.events"][field] = invalid
            self.state.write_text(json.dumps(state, sort_keys=True))
            before = self.state.read_bytes()

            # When provisioning encounters incompatible partitions, replicas or config.
            result = self.provision()

            # Then it fails without destructive repair.
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(before, self.state.read_bytes())

    def test_failed_commands_do_not_echo_secret_output(self):
        for command, invoke in (("kafka-storage.sh", self.start), ("kafka-topics.sh", self.provision),
                                ("kafka-acls.sh", self.provision)):
            with self.subTest(command=command):
                # Given a CLI failing with sensitive diagnostic text.
                self.env["FAKE_FAIL"] = command

                # When its caller invokes it.
                result = invoke()

                # Then only safe messages are emitted.
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn("SECRET_FROM_FAILED_COMMAND", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
