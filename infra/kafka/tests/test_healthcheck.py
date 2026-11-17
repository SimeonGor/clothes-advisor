"""Native healthcheck CLI contract with sanitized credentials and executable doubles."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


KAFKA = Path(__file__).resolve().parents[1]


class HealthcheckCliTest(unittest.TestCase):
    def setUp(self):
        self.workspace = tempfile.TemporaryDirectory()
        self.addCleanup(self.workspace.cleanup)
        self.base = Path(self.workspace.name)
        self.bin = self.base / "bin"
        self.bin.mkdir()
        for name in ("timeout", "kcat", "java", "kafka-broker-api-versions.sh"):
            executable = self.bin / name
            shutil.copyfile(KAFKA / "tests/fixtures/healthcheck_cli.py", executable)
            executable.chmod(0o700)
        self.calls = self.base / "calls.jsonl"
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                        HOSTNAME="kafka-2", FAKE_CALLS=str(self.calls))
        self.credentials = self.base / "node credentials"
        self.credentials.mkdir()
        self.ca = "-----BEGIN CERTIFICATE-----\nPUBLIC_CA_FIXTURE\n-----END CERTIFICATE-----\n"
        escaped_ca = self.ca.replace("\n", "\\n")
        (self.credentials / "client.properties").write_text(
            "security.protocol=SSL\nssl.keystore.key=PRIVATE_KEY_FIXTURE\n"
            f"ssl.truststore.certificates={escaped_ca}\n")
        (self.credentials / "certificate.pem").write_text("CLIENT_CERTIFICATE_FIXTURE\n")
        (self.credentials / "key.pem").write_text("PRIVATE_KEY_FIXTURE\n")

    def healthcheck(self):
        return subprocess.run(["bash", str(KAFKA / "healthcheck.sh"), str(self.credentials)],
                              env=self.env, text=True, capture_output=True)

    def test_metadata_uses_only_local_broker_verified_mtls_and_native_deadline(self):
        # Given existing node credentials, including an escaped public CA in Java properties.
        before = {path.name: (path.read_bytes(), path.stat().st_mtime_ns)
                  for path in self.credentials.iterdir()}

        # When the Metadata CLI succeeds.
        result = self.healthcheck()

        # Then only timeout and kcat run, with one broker, verified mTLS and no key in argv.
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = [json.loads(line) for line in self.calls.read_text().splitlines()]
        self.assertEqual([call[0] for call in calls], ["timeout", "kcat"])
        self.assertEqual(calls[0], ["timeout", "-s", "KILL", "15", *calls[1]])
        self.assertEqual(calls[1][1:6], ["-b", "kafka-2:9094", "-L", "-m", "10"])
        config = calls[1][6:]
        self.assertEqual(config[::2], ["-X"] * 7)
        self.assertCountEqual(config[1::2], [
            "security.protocol=ssl", "ssl.ca.pem=" + self.ca,
            "ssl.certificate.location=" + str(self.credentials / "certificate.pem"),
            "ssl.key.location=" + str(self.credentials / "key.pem"),
            "enable.ssl.certificate.verification=true", "ssl.endpoint.identification.algorithm=https",
            "allow.auto.create.topics=false",
        ])
        self.assertNotIn("PRIVATE_KEY_FIXTURE", self.calls.read_text() + result.stdout + result.stderr)
        self.assertEqual(before, {path.name: (path.read_bytes(), path.stat().st_mtime_ns)
                                  for path in self.credentials.iterdir()})

    def test_native_failure_status_propagates(self):
        for status in (1, 137):
            with self.subTest(status=status):
                # Given an unsuccessful native process.
                self.env["FAKE_KCAT_STATUS"] = str(status)

                # When the healthcheck runs, then it preserves the failure status.
                self.assertEqual(self.healthcheck().returncode, status)

    def test_missing_or_empty_ca_fails_before_native_cli(self):
        for content in ("security.protocol=SSL\n", "ssl.truststore.certificates=\n"):
            with self.subTest(content=content):
                # Given a node config with no public CA value.
                (self.credentials / "client.properties").write_text(content)

                # When the healthcheck runs, then it fails without starting a native client.
                result = self.healthcheck()
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(self.calls.exists())
                self.assertNotIn("PRIVATE_KEY_FIXTURE", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
