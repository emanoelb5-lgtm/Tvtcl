#!/usr/bin/env python3
"""Owner-side signer. Key/password paths are private and never committed or printed."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument("--unsigned", required=True, type=Path)
parser.add_argument("--private-backup", required=True, type=Path)
parser.add_argument("--output", required=True, type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
expected = (root / "signing/certificate.sha256").read_text().strip().lower()
source = json.loads((args.unsigned / "source-manifest.json").read_text())
private = json.loads((args.private_backup / "assinatura.json").read_text())
if private["certificate_sha256"] != expected:
    raise SystemExit("A chave não corresponde ao certificado permanente do projeto.")
for name, digest in source["files"].items():
    if Path(name).name != name or hashlib.sha256((args.unsigned / name).read_bytes()).hexdigest() != digest:
        raise SystemExit("O artefato unsigned não corresponde ao manifesto da compilação.")
args.output.mkdir(parents=True, exist_ok=True)
env = os.environ.copy()
env["TCL_STORE_PASS"] = private["store_password"]
env["TCL_KEY_PASS"] = private["key_password"]
signer = ["java", "-jar", str(args.unsigned / "apksigner.jar")]
files = {}
for input_name, output_name in [("release-unsigned.apk", "TCL-Light-" + source["version_name"] + ".apk"),
    ("upgrade-probe-unsigned.apk", "upgrade-probe.apk")]:
    output = args.output / output_name
    subprocess.run(signer + ["sign", "--ks", str(args.private_backup / private["keystore"]),
        "--ks-key-alias", private["alias"], "--ks-pass", "env:TCL_STORE_PASS",
        "--key-pass", "env:TCL_KEY_PASS", "--out", str(output), str(args.unsigned / input_name)],
        env=env, check=True, capture_output=True)
    verified = subprocess.run(signer + ["verify", "--verbose", "--print-certs", str(output)],
        check=True, capture_output=True, text=True).stdout
    fingerprints = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)", verified)
    if fingerprints != [expected]:
        raise SystemExit("Assinatura diferente da permanente: publicação bloqueada.")
    files[output_name] = hashlib.sha256(output.read_bytes()).hexdigest()
(args.output / "manifest.json").write_text(json.dumps({**{k: v for k, v in source.items() if k != "files"},
    "certificate_sha256": expected, "files": files}, indent=2) + "\n")
name = "TCL-Light-" + source["version_name"] + ".apk"
(args.output / "TCL-Light-atualizacao.json").write_text(json.dumps({
    "schema": 1, "package": source["package"], "version_name": source["version_name"],
    "version_code": source["version_code"], "min_sdk": 23,
    "apk_url": "https://github.com/emanoelb5-lgtm/Tvtcl/releases/download/v" + source["version_name"] + "/" + name,
    "size_bytes": (args.output / name).stat().st_size, "sha256": files[name],
    "certificate_sha256": expected}, indent=2) + "\n")
print("APKs assinados e conferidos com o certificado permanente: " + expected)
