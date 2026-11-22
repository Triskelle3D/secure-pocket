#!/usr/bin/env python3
"""Create persistent private-CA and Docling server certificates for Compose."""
from datetime import datetime, timedelta, timezone
import os
from pathlib import Path
import secrets
import tempfile
import time

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID


STATE = Path("/tls-state")
CA_DIR = Path("/docling-ca")
SERVER_DIR = Path("/docling-server")
AUTH_DIR = Path("/docling-auth")


def save_private(path: Path, content: bytes, mode: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(prefix=".tls-", dir=path.parent)
    try:
        os.fchmod(fd, mode)
        with os.fdopen(fd, "wb") as target:
            target.write(content)
            target.flush()
            os.fsync(target.fileno())
        os.replace(name, path)
    finally:
        Path(name).unlink(missing_ok=True)


def generate() -> None:
    ca_key_path = STATE / "ca.key"
    ca_cert_path = CA_DIR / "ca.crt"
    cert_path = SERVER_DIR / "tls.crt"
    key_path = SERVER_DIR / "tls.key"
    CA_DIR.mkdir(parents=True, exist_ok=True)
    SERVER_DIR.mkdir(parents=True, exist_ok=True)
    AUTH_DIR.mkdir(parents=True, exist_ok=True)
    STATE.mkdir(parents=True, exist_ok=True)

    api_key_path = AUTH_DIR / "api.key"
    if api_key_path.exists():
        if len(api_key_path.read_text(encoding="utf-8").strip()) < 32:
            raise RuntimeError("Internal Docling API key is invalid")
    else:
        save_private(api_key_path, (secrets.token_urlsafe(32) + "\n").encode("ascii"), 0o444)

    if ca_key_path.exists() and ca_cert_path.exists():
        ca_key = serialization.load_pem_private_key(ca_key_path.read_bytes(), password=None)
        ca_cert = x509.load_pem_x509_certificate(ca_cert_path.read_bytes())
        if ca_key.public_key().public_numbers() != ca_cert.public_key().public_numbers():
            raise RuntimeError("Internal Docling CA certificate does not match its private key")
    elif ca_key_path.exists() or ca_cert_path.exists():
        raise RuntimeError("Private CA files are incomplete; preserve backups and repair the TLS volumes")
    else:
        ca_key = ec.generate_private_key(ec.SECP256R1())
        now = datetime.now(timezone.utc)
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Secure Pocket internal Docling CA")])
        ca_cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name)
                   .public_key(ca_key.public_key()).serial_number(x509.random_serial_number())
                   .not_valid_before(now - timedelta(minutes=5))
                   .not_valid_after(now + timedelta(days=3650))
                   .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
                   .add_extension(x509.KeyUsage(digital_signature=True, key_encipherment=False,
                        content_commitment=False, data_encipherment=False, key_agreement=False,
                        key_cert_sign=True, crl_sign=True, encipher_only=False, decipher_only=False), critical=True)
                   .sign(ca_key, hashes.SHA256()))
        save_private(ca_key_path, ca_key.private_bytes(serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8, serialization.NoEncryption()), 0o400)
        save_private(ca_cert_path, ca_cert.public_bytes(serialization.Encoding.PEM), 0o444)

    if cert_path.exists() and key_path.exists():
        server_cert = x509.load_pem_x509_certificate(cert_path.read_bytes())
        server_key = serialization.load_pem_private_key(key_path.read_bytes(), password=None)
        if (server_cert.issuer != ca_cert.subject
                or server_cert.public_key().public_numbers() != server_key.public_key().public_numbers()):
            raise RuntimeError("Existing Docling certificate does not match the private CA and key")
        san = server_cert.extensions.get_extension_for_class(x509.SubjectAlternativeName).value
        if "docling" not in san.get_values_for_type(x509.DNSName):
            raise RuntimeError("Existing Docling certificate is missing the docling DNS SAN")
        ca_key.public_key().verify(server_cert.signature, server_cert.tbs_certificate_bytes,
                                   ec.ECDSA(server_cert.signature_hash_algorithm))
        if server_cert.not_valid_after_utc > datetime.now(timezone.utc) + timedelta(days=90):
            print("Using persistent internal Docling TLS certificates")
            return
    elif cert_path.exists() or key_path.exists():
        raise RuntimeError("Docling TLS certificate files are incomplete; repair the TLS volume")

    server_key = ec.generate_private_key(ec.SECP256R1())
    now = datetime.now(timezone.utc)
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "docling")])
    cert = (x509.CertificateBuilder().subject_name(subject).issuer_name(ca_cert.subject)
            .public_key(server_key.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(now - timedelta(minutes=5))
            .not_valid_after(now + timedelta(days=825))
            .add_extension(x509.SubjectAlternativeName([x509.DNSName("docling")]), critical=False)
            .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
            .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
            .sign(ca_key, hashes.SHA256()))
    save_private(key_path, server_key.private_bytes(serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8, serialization.NoEncryption()), 0o400)
    save_private(cert_path, cert.public_bytes(serialization.Encoding.PEM), 0o444)
    print("Created persistent internal Docling TLS certificates")


if __name__ == "__main__":
    generate()
    while True:
        time.sleep(6 * 60 * 60)
        generate()
