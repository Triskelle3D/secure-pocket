"""Authenticated encryption helpers for Secure Pocket Link's local data.

The file format is intentionally simple and versioned: magic, a random 96-bit
AES-GCM nonce, ciphertext, and the 128-bit authentication tag. Callers write to
a private temporary file and atomically replace the destination only after the
tag has been finalized/verified.
"""
import base64
import json
import os
import secrets
import stat
import tempfile
from pathlib import Path

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM


MAGIC = b"SPKENC1\0"
NONCE_SIZE = 12
TAG_SIZE = 16
CHUNK_SIZE = 1024 * 1024


def load_or_create_key(path):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.is_symlink():
        raise ValueError("Encryption key path must not be a symbolic link")
    try:
        flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
        fd = os.open(path, flags)
        try:
            file_stat = os.fstat(fd)
            if path.is_symlink() or not stat.S_ISREG(file_stat.st_mode):
                raise ValueError("Encryption key path must be a regular file, not a symbolic link")
            with os.fdopen(fd, "rb") as stream:
                key = stream.read(33)
        except BaseException:
            os.close(fd)
            raise
    except FileNotFoundError:
        key = secrets.token_bytes(32)
        nofollow = getattr(os, "O_NOFOLLOW", 0)
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | nofollow, 0o600)
        try:
            with os.fdopen(fd, "wb") as stream:
                stream.write(key)
                stream.flush()
                os.fsync(stream.fileno())
        except BaseException:
            path.unlink(missing_ok=True)
            raise
        _fsync_directory(path.parent)
    if len(key) != 32:
        raise ValueError(f"Encryption key file {path} must contain exactly 32 bytes")
    if not path.is_file() or path.is_symlink():
        raise ValueError("Encryption key path must be a regular file")
    if hasattr(os, "supports_follow_symlinks") and os.chmod in os.supports_follow_symlinks:
        try:
            os.chmod(path, 0o600, follow_symlinks=False)
        except OSError:
            pass
    return key


def is_encrypted(path):
    try:
        with open(path, "rb") as stream:
            return stream.read(len(MAGIC)) == MAGIC
    except OSError:
        return False


def _file_aad(artifact_id):
    return MAGIC + b"file\0" + str(artifact_id).encode("utf-8")


def encrypt_stream(source, destination, key, artifact_id):
    nonce = secrets.token_bytes(NONCE_SIZE)
    header = MAGIC + nonce
    encryptor = Cipher(algorithms.AES(key), modes.GCM(nonce)).encryptor()
    encryptor.authenticate_additional_data(_file_aad(artifact_id))
    destination.write(header)
    for chunk in iter(lambda: source.read(CHUNK_SIZE), b""):
        destination.write(encryptor.update(chunk))
    destination.write(encryptor.finalize())
    destination.write(encryptor.tag)


def decrypt_stream(source, destination, key, artifact_id):
    header = source.read(len(MAGIC) + NONCE_SIZE)
    if len(header) != len(MAGIC) + NONCE_SIZE or not header.startswith(MAGIC):
        raise ValueError("Encrypted artifact header is invalid")
    source.seek(0, os.SEEK_END)
    size = source.tell()
    ciphertext_size = size - len(header) - TAG_SIZE
    if ciphertext_size < 0:
        raise ValueError("Encrypted artifact is truncated")
    source.seek(size - TAG_SIZE)
    tag = source.read(TAG_SIZE)
    source.seek(len(header))
    nonce = header[len(MAGIC):]
    decryptor = Cipher(algorithms.AES(key), modes.GCM(nonce, tag)).decryptor()
    decryptor.authenticate_additional_data(_file_aad(artifact_id))
    remaining = ciphertext_size
    while remaining:
        chunk = source.read(min(CHUNK_SIZE, remaining))
        if not chunk:
            raise ValueError("Encrypted artifact is truncated")
        remaining -= len(chunk)
        destination.write(decryptor.update(chunk))
    destination.write(decryptor.finalize())


def encrypt_file_atomic(path, key):
    """Encrypt a file in place without exposing a partial ciphertext."""
    path = Path(path)
    fd, temp_name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".enc", dir=path.parent)
    temp = Path(temp_name)
    try:
        with path.open("rb") as source, os.fdopen(fd, "wb") as destination:
            encrypt_stream(source, destination, key, path.name)
            destination.flush()
            os.fsync(destination.fileno())
        os.replace(temp, path)
        _fsync_directory(path.parent)
    finally:
        temp.unlink(missing_ok=True)


def decrypt_to_temporary(source, key, artifact_id):
    """Return a private temporary handle only after complete GCM authentication."""
    destination = tempfile.TemporaryFile(mode="w+b")
    try:
        decrypt_stream(source, destination, key, artifact_id)
        destination.flush()
        destination.seek(0)
        return destination
    except BaseException:
        destination.close()
        raise


def _fsync_directory(path):
    try:
        fd = os.open(path, os.O_RDONLY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)
    except OSError:
        pass


def seal_json(value, key):
    nonce = secrets.token_bytes(NONCE_SIZE)
    plaintext = json.dumps(value, separators=(",", ":")).encode("utf-8")
    ciphertext = AESGCM(key).encrypt(nonce, plaintext, MAGIC + b"config")
    return base64.b64encode(nonce + ciphertext).decode("ascii")


def unseal_json(value, key):
    raw = base64.b64decode(value, validate=True)
    if len(raw) < NONCE_SIZE + TAG_SIZE:
        raise ValueError("Encrypted settings are truncated")
    plaintext = AESGCM(key).decrypt(raw[:NONCE_SIZE], raw[NONCE_SIZE:], MAGIC + b"config")
    return json.loads(plaintext.decode("utf-8"))
