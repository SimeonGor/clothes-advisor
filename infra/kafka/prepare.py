#!/usr/bin/env python3
"""Prepare immutable local Kafka credentials outside the repository; never rotate."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import stat
import subprocess
import sys
import uuid


ROOT = Path(__file__).resolve().parents[2]
PRINCIPALS = ("bootstrap-admin", "account-service", "wardrobe-service",
              "recommendation-service", "notification-service")
CONFIG = {"version": 1, "nodes": [1, 2, 3], "principals": PRINCIPALS,
          "mechanism": "SCRAM-SHA-512", "iterations": 8192,
          "server_template": hashlib.sha256((Path(__file__).parent / "server.properties").read_bytes()).hexdigest()}


def openssl(*args):
    result = subprocess.run(["openssl", *map(str, args)], capture_output=True)
    if result.returncode:
        raise ValueError("OpenSSL failed; partial credentials retained, do not use them")


def write(path, text, mode=0o444):
    with path.open("x") as output:
        output.write(text)
    path.chmod(mode)


def pem(path):
    return path.read_text().strip().replace("\n", "\\n")


def hashes(directory):
    result = {}
    for path in sorted(directory.rglob("*")):
        if path.is_symlink():
            raise ValueError("Symlinks are not allowed in credentials")
        if path.is_file() and path.name != "manifest.json":
            result[str(path.relative_to(directory))] = hashlib.sha256(path.read_bytes()).hexdigest()
    return result


def prepare(directory):
    if not directory.is_absolute() or directory.is_symlink():
        raise ValueError("Use an absolute, nonsymlink credentials directory outside the repository")
    directory = directory.resolve()
    if directory == ROOT or ROOT in directory.parents:
        raise ValueError("Credentials must be outside the repository")
    if directory.exists():
        if not directory.is_dir() or directory.stat().st_uid != os.getuid():
            raise ValueError("Credentials directory must belong to the current user")
        if stat.S_IMODE(directory.stat().st_mode) != 0o700:
            raise ValueError("Credentials directory must have mode 0700")
        manifest = directory / "manifest.json"
        if not manifest.is_file() or manifest.is_symlink():
            raise ValueError("Partial credentials: manifest missing; nothing was changed")
        saved = json.loads(manifest.read_text())
        expected = json.loads(json.dumps(CONFIG))
        if not isinstance(saved, dict) or saved.get("config") != expected or saved.get("files") != hashes(directory):
            raise ValueError("Credentials/config mismatch; nothing was changed")
        for node in CONFIG["nodes"]:
            openssl("x509", "-in", directory / f"kafka-{node}/certificate.pem", "-checkend", "0", "-noout")
        print("Existing Kafka credentials verified; unchanged")
        return

    directory.mkdir(mode=0o700)
    private = directory / "private"
    private.mkdir(mode=0o700)
    ca_key, ca_cert = private / "ca.key", private / "ca.pem"
    openssl("genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", ca_key)
    ca_key.chmod(0o600)
    openssl("req", "-new", "-x509", "-key", ca_key, "-sha256", "-days", "3650",
            "-subj", "/CN=clothes-advisor-kafka-ca", "-out", ca_cert,
            "-addext", "basicConstraints=critical,CA:TRUE",
            "-addext", "keyUsage=critical,keyCertSign,cRLSign")
    cluster_id = base64.urlsafe_b64encode(uuid.uuid4().bytes).decode().rstrip("=")
    scram = []
    for principal in PRINCIPALS:
        client = directory / principal
        client.mkdir(mode=0o755)
        client.chmod(0o755)
        password = secrets.token_urlsafe(32)
        salt = secrets.token_bytes(32)
        salted = hashlib.pbkdf2_hmac("sha512", password.encode(), salt, CONFIG["iterations"])
        scram.append(f'SCRAM-SHA-512=[name={principal},iterations=8192,'
                     f'salt="{base64.b64encode(salt).decode()}",'
                     f'saltedpassword="{base64.b64encode(salted).decode()}"]')
        write(client / "client.properties", "\n".join([
            "bootstrap.servers=kafka-1:9092,kafka-2:9092,kafka-3:9092",
            "security.protocol=SASL_SSL", "sasl.mechanism=SCRAM-SHA-512",
            'sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required '
            f'username="{principal}" password="{password}";',
            "ssl.truststore.type=PEM", f"ssl.truststore.certificates={pem(ca_cert)}",
            "ssl.endpoint.identification.algorithm=https", "acks=all", "enable.idempotence=true",
            "enable.auto.commit=false", ""]))
    template = (Path(__file__).parent / "server.properties").read_text()
    for node in CONFIG["nodes"]:
        name = f"kafka-{node}"
        target = directory / name
        target.mkdir(mode=0o755)
        target.chmod(0o755)
        key, certificate = target / "key.pem", target / "certificate.pem"
        csr, extensions = private / f"{name}.csr", private / f"{name}.extensions"
        openssl("genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", key)
        openssl("req", "-new", "-key", key, "-subj", f"/CN={name}", "-out", csr)
        write(extensions, f"subjectAltName=DNS:{name}\nbasicConstraints=critical,CA:FALSE\n"
              "keyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth,clientAuth\n")
        openssl("x509", "-req", "-in", csr, "-CA", ca_cert, "-CAkey", ca_key,
                "-set_serial", str(secrets.randbits(128)), "-days", "365", "-sha256",
                "-extfile", extensions, "-out", certificate)
        key.chmod(0o444)
        certificate.chmod(0o444)
        tls = (f"ssl.keystore.type=PEM\nssl.keystore.key={pem(key)}\n"
               f"ssl.keystore.certificate.chain={pem(certificate)}\n"
               f"ssl.truststore.type=PEM\nssl.truststore.certificates={pem(ca_cert)}\n"
               "ssl.endpoint.identification.algorithm=https\n")
        write(target / "server.properties", template.replace("@NODE@", str(node)) + tls)
        write(target / "client.properties", "security.protocol=SSL\n" + tls)
        write(target / "cluster.id", cluster_id + "\n")
        write(target / "node.id", str(node) + "\n")
        write(target / "scram.txt", "\n".join(scram) + "\n")
    write(directory / "manifest.json", json.dumps({"config": CONFIG, "files": hashes(directory)},
                                                  indent=2) + "\n", 0o600)
    print("Kafka credentials prepared; keep this directory and data volumes together")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--secrets-dir", required=True, type=Path)
    args = parser.parse_args()
    os.umask(0o077)
    try:
        prepare(args.secrets_dir)
    except (OSError, ValueError) as error:
        # Never echo subprocess output, configuration contents or secrets.
        message = str(error) if isinstance(error, ValueError) and not isinstance(error, json.JSONDecodeError) else "Cannot prepare/verify credentials; check private directory and OpenSSL"
        print(message, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
