#!/usr/bin/env python3
"""生成仅用于 verification 包和回环服务的 TLS 证书，私钥保留于内部临时目录。"""
import os
import subprocess
from pathlib import Path


def main() -> None:
    root = Path(__file__).resolve().parents[2]
    directory = root / ".superpowers/tmp/tls-fixture"
    directory.mkdir(parents=True, exist_ok=True)
    directory.chmod(0o700)
    key = directory / "server.key"
    certificate = directory / "server.pem"
    previous = os.umask(0o077)
    try:
        subprocess.run([
            "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
            "-keyout", str(key), "-out", str(certificate), "-days", "3650",
            "-subj", "/CN=Pathfin Local Verification Only",
            "-addext", "subjectAltName=DNS:localhost,IP:127.0.0.1",
            "-addext", "basicConstraints=critical,CA:TRUE",
            "-addext", "extendedKeyUsage=serverAuth",
        ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    finally:
        os.umask(previous)
    target = root / "android/app/src/verification/res/raw/pathfin_fixture_ca.pem"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(certificate.read_bytes())
    print("已生成回环 TLS 验证证书；重新构建 verification 包后使用。私钥未复制进源码。")


if __name__ == "__main__":
    main()
