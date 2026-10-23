#!/usr/bin/env python3
"""Stage aligned release APKs and a signing tool; never stage a private key."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

root = Path(__file__).resolve().parents[1]
os.chdir(root)
version = json.loads((root / "signing/version.json").read_text())
stage = root / "dist/unsigned"
stage.mkdir(parents=True, exist_ok=True)
tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/35.0.0"
unsigned = root / "app/build/outputs/apk/release/app-release-unsigned.apk"

def build(code, name):
    subprocess.run(["gradle", ":app:assembleRelease", f"-PverificationVersionCode={code}",
        "--no-daemon", "--stacktrace"], check=True)
    subprocess.run([str(tools / "zipalign"), "-f", "-p", "4", str(unsigned), str(stage / name)], check=True)

build(version["version_code"], "release-unsigned.apk")
build(version["upgrade_probe_code"], "upgrade-probe-unsigned.apk")
shutil.copy2(tools / "lib/apksigner.jar", stage / "apksigner.jar")
files = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in stage.iterdir() if p.is_file()}
(stage / "source-manifest.json").write_text(json.dumps({**version,
    "source_commit": os.environ["GITHUB_SHA"], "files": files}, indent=2) + "\n")
print("Unsigned release and internal upgrade probe staged; private signing remains outside GitHub.")
