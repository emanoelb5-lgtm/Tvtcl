#!/usr/bin/env python3
"""Publication guard: package, version, build provenance, digest and fixed signing certificate."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parents[1]
stage = root / "dist/signed"
manifest = json.loads((stage / "manifest.json").read_text())
version = json.loads((root / "signing/version.json").read_text())
expected = (root / "signing/certificate.sha256").read_text().strip().lower()
# Intentionally pinned: changing only a metadata file cannot silently rotate the key.
PINNED_CERTIFICATE = "21e8b079624ca39fcb84817f2a711c46e474d2ccdb65f6e629e26fe153fdb688"
assert expected == PINNED_CERTIFICATE == manifest["certificate_sha256"], "Certificado permanente alterado"
assert all(manifest[k] == v for k, v in version.items()), "Versão não corresponde ao fonte"
assert re.fullmatch(r"[0-9a-f]{40}", manifest["source_commit"]), "Commit de origem inválido"
subprocess.run(["git", "cat-file", "-e", manifest["source_commit"] + "^{commit}"], check=True)
tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/35.0.0"
for name, code in [("TCL-Light-" + version["version_name"] + ".apk", version["version_code"]),
    ("upgrade-probe.apk", version["upgrade_probe_code"])]:
    apk = stage / name
    assert hashlib.sha256(apk.read_bytes()).hexdigest() == manifest["files"][name], "APK modificado"
    output = subprocess.run([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)],
        check=True, capture_output=True, text=True).stdout
    certs = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)", output)
    assert certs == [expected], "APK assinado com outra chave: publicação bloqueada"
    badging = subprocess.run([str(tools / "aapt"), "dump", "badging", str(apk)],
        check=True, capture_output=True, text=True).stdout
    assert f"name='{version['package']}'" in badging.splitlines()[0], "Pacote incorreto"
    assert f"versionCode='{code}'" in badging.splitlines()[0], "Código de versão incorreto"
    assert f"versionName='{version['version_name']}'" in badging.splitlines()[0], "Nome de versão incorreto"
    assert "application-debuggable" not in badging, "APK de teste não pode ser publicado"
    print(f"Verified {name}: code={code}; certificate={expected}")
