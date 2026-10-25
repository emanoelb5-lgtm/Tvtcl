#!/usr/bin/env python3
"""Pinned public 0.7.0 certificate fixture and 0.12.0 upgrade baseline, never runtime assets."""
import hashlib
from pathlib import Path
import shutil
import urllib.request

root = Path(__file__).resolve().parents[1]
apk = root / "dist/baseline/TCL-Light-0.7.0.apk"
expected = "11c146d64e8031d3d29e737166e901f5b1ae4ee04d09883ff32eb7bc160f218a"
apk.parent.mkdir(parents=True, exist_ok=True)
if not apk.is_file() or hashlib.sha256(apk.read_bytes()).hexdigest() != expected:
    url = "https://github.com/emanoelb5-lgtm/Tvtcl/releases/download/v0.7.0/TCL-Light-0.7.0.apk"
    with urllib.request.urlopen(url, timeout=60) as response:
        data = response.read(4 * 1024 * 1024)
    assert hashlib.sha256(data).hexdigest() == expected, "A versão de referência foi alterada"
    apk.write_bytes(data)
fixture = root / "app/src/androidTest/assets/previous-release.apk"
fixture.parent.mkdir(parents=True, exist_ok=True)
shutil.copyfile(apk, fixture)
print("Official 0.7.0 baseline verified and available to tests")
current = root / "dist/baseline/TCL-Light-0.12.0.apk"
digest = "4ee4197805211be8f91e6625cff58a622dbcb0d5672db4786c04f91c4c46225f"
if not current.is_file() or hashlib.sha256(current.read_bytes()).hexdigest() != digest:
    with urllib.request.urlopen("https://github.com/emanoelb5-lgtm/Tvtcl/releases/download/v0.12.0/TCL-Light-0.12.0.apk", timeout=60) as response:
        data = response.read(4 * 1024 * 1024)
    assert hashlib.sha256(data).hexdigest() == digest, "A versão 0.12.0 de referência foi alterada"
    current.write_bytes(data)
print("Official 0.12.0 baseline verified for in-place updates")
