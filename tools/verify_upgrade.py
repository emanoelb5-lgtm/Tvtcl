#!/usr/bin/env python3
"""Exercise a real PackageManager update and retain Home choice plus an actual UI preference."""
import atexit
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
version = json.loads((root / "signing/version.json").read_text())
pkg = version["package"]
qa = root / "dist/upgrade-qa"
qa.mkdir(parents=True, exist_ok=True)

def adb(*args):
    return subprocess.run(["adb", *args], check=True, capture_output=True, text=True).stdout.strip()

def shell(*args):
    return adb("shell", *args)

def diagnostics():
    for name, command in [("runtime.txt", ("logcat", "-d", "-s", "AndroidRuntime:E")),
        ("window.txt", ("dumpsys", "window", "windows")),
        ("activities.txt", ("dumpsys", "activity", "activities"))]:
        try: (qa / name).write_text(shell(*command))
        except Exception: pass
    try:
        shell("uiautomator", "dump", "/sdcard/tcl-upgrade-final.xml")
        (qa / "final.xml").write_text(shell("cat", "/sdcard/tcl-upgrade-final.xml"))
        shell("screencap", "-p", "/sdcard/tcl-upgrade-final.png")
        adb("pull", "/sdcard/tcl-upgrade-final.png", str(qa / "final.png"))
    except Exception: pass

atexit.register(diagnostics)

def snapshot():
    shell("uiautomator", "dump", "/sdcard/tcl-upgrade.xml")
    return ET.fromstring(shell("cat", "/sdcard/tcl-upgrade.xml"))

def eco_node():
    for _ in range(6):
        tree = snapshot()
        for node in tree.iter("node"):
            if node.get("content-desc", "").startswith("Modo leve.") and node.get("clickable") == "true":
                return node
        time.sleep(.3)
    raise RuntimeError("O cartão Modo leve não apareceu na central")

def open_home():
    result = shell("am", "start", "-W", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME")
    (qa / "home-launch.txt").write_text(result)
    for _ in range(12):
        tree = snapshot()
        if any(n.get("package") == pkg and n.get("text") == "Garrafão do Norte" for n in tree.iter("node")):
            return
        time.sleep(.5)
    raise RuntimeError("A janela da tela inicial não ficou pronta")

def resolved_home():
    return shell("cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.MAIN",
        "-c", "android.intent.category.HOME").splitlines()[-1]

assert "Success" in adb("install", str(root / "dist/signed" / ("TCL-Light-" + version["version_name"] + ".apk")))
assert "Success" in shell("cmd", "package", "set-home-activity", "--user", "0", pkg + "/.MainActivity")
open_home()
shell("input", "keyevent", "82")
before = eco_node()
points = list(map(int, re.findall(r"\d+", before.attrib["bounds"])))
shell("input", "tap", str((points[0] + points[2]) // 2), str((points[1] + points[3]) // 2))
saved = eco_node().attrib["content-desc"]
assert saved != before.attrib["content-desc"], "A preferência não foi alterada"
shell("screencap", "-p", "/sdcard/tcl-upgrade-before.png")
adb("pull", "/sdcard/tcl-upgrade-before.png", str(qa / "before.png"))
home = resolved_home()
assert home.startswith(pkg + "/"), "O launcher não se tornou Home"
result = adb("install", "-r", str(root / "dist/signed/upgrade-probe.apk"))
assert "Success" in result, "O Android recusou a atualização sobre a versão instalada"
assert resolved_home() == home, "A atualização perdeu a escolha da tela inicial"
open_home()
shell("input", "keyevent", "82")
assert eco_node().attrib["content-desc"] == saved, "A atualização perdeu a preferência salva"
shell("screencap", "-p", "/sdcard/tcl-upgrade-after.png")
adb("pull", "/sdcard/tcl-upgrade-after.png", str(qa / "after.png"))
report = {"version": version["version_name"], "installed_code": version["version_code"],
    "internal_upgrade_code": version["upgrade_probe_code"], "update_result": "Success",
    "home_preserved": True, "preference_preserved": True,
    "certificate_sha256": (root / "signing/certificate.sha256").read_text().strip()}
(qa / ("TCL-Light-" + version["version_name"] + "-verificacao.json")).write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps(report, indent=2))
