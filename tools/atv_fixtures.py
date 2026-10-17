"""Generate the fixed certificates and golden pairing-secret vectors used by the
Android TV protocol tests (testing/fakes/src/main/resources/atv/).

Run once with Python 3 and `cryptography`; the outputs are committed, so tests
never generate keys. The digest is computed with the exact expressions from
androidtvremote2 pairing.py (tronikos/androidtvremote2, Apache-2.0,
commit e664a8e1f14ee458b14c646bfb3ea46f10367051), so the Kotlin port is checked
against the reference implementation rather than against itself.

    python tools/atv_fixtures.py testing/fakes/src/main/resources/atv
"""

from __future__ import annotations

import datetime
import hashlib
import json
import sys
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

DN_QUALIFIER = x509.ObjectIdentifier("2.5.4.46")

IDENTITIES = {
    # The phone. Same shape as androidtvremote2 certificate_generator.py.
    "client": [x509.NameAttribute(NameOID.COMMON_NAME, "Sceptrum")],
    # A second phone, for "TV does not know this client" tests.
    "stranger": [x509.NameAttribute(NameOID.COMMON_NAME, "Stranger")],
    # A TV, SHIELD-style subject.
    "tv": [x509.NameAttribute(NameOID.COMMON_NAME, "atvremote/darcy/darcy/SHIELD Android TV/AA:BB:CC:DD:EE:01")],
    # The same TV after a factory reset: new key, Nexus-style subject.
    "tv_reset": [
        x509.NameAttribute(DN_QUALIFIER, "fugu/fugu/Nexus Player"),
        x509.NameAttribute(NameOID.COMMON_NAME, "atvremote/AA:BB:CC:DD:EE:02"),
    ],
}


def make(name: str, attrs: list[x509.NameAttribute], out: Path) -> x509.Certificate:
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    subject = x509.Name(attrs)
    start = datetime.datetime(2026, 1, 1, tzinfo=datetime.timezone.utc)
    cert = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(subject)
        .public_key(key.public_key())
        .serial_number(1000)
        .not_valid_before(start)
        .not_valid_after(start + datetime.timedelta(days=50 * 365))
        .add_extension(x509.BasicConstraints(ca=True, path_length=0), False)
        .sign(key, hashes.SHA256())
    )
    (out / f"{name}.crt.pem").write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    (out / f"{name}.key.pem").write_bytes(
        key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
    )
    return cert


def secret(client_cert: x509.Certificate, server_cert: x509.Certificate, pairing_code: str) -> bytes:
    # Verbatim from androidtvremote2 pairing.py async_finish_pairing.
    client_modulus, client_exponent = client_cert.public_key().public_numbers().n, client_cert.public_key().public_numbers().e
    server_modulus, server_exponent = server_cert.public_key().public_numbers().n, server_cert.public_key().public_numbers().e
    h = hashlib.sha256()
    h.update(bytes.fromhex(f"{client_modulus:X}"))
    h.update(bytes.fromhex(f"0{client_exponent:X}"))
    h.update(bytes.fromhex(f"{server_modulus:X}"))
    h.update(bytes.fromhex(f"0{server_exponent:X}"))
    h.update(bytes.fromhex(pairing_code[2:]))
    return h.digest()


def main() -> None:
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    certs = {name: make(name, attrs, out) for name, attrs in IDENTITIES.items()}
    vectors = []
    for client, server in (("client", "tv"), ("client", "tv_reset"), ("stranger", "tv")):
        for suffix in ("A1C9", "0000", "FFFF", "4F2E"):
            digest = secret(certs[client], certs[server], "XX" + suffix)
            code = f"{digest[0]:02X}{suffix}"
            assert digest[0] == int(code[0:2], 16)
            vectors.append({"client": client, "server": server, "code": code, "secret": digest.hex().upper()})
    (out / "secret_vectors.json").write_text(json.dumps(vectors, indent=2) + "\n")


if __name__ == "__main__":
    main()
