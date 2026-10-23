#!/usr/bin/env python3
"""Exercise a real PackageManager update, preserve preferences and report native Home constraints."""
import atexit
import json
from pathlib import Path
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
    # Some TV images keep a persistent OEM Home even after set-home-activity says Success.
    # Test the actual release UI explicitly in that case; never disable the stock launcher.
    request = ("-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME") \
        if resolved_home().startswith(pkg + "/") else ("-n", pkg + "/.MainActivity")
    result = shell("am", "start", "-W", *request)
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
home_request = shell("cmd", "package", "set-home-activity", "--user", "0", pkg + "/.MainActivity")
(qa / "home-request.txt").write_text(home_request)
assert "Success" in home_request
initial_home = resolved_home()
custom_home_selected = initial_home.startswith(pkg + "/")
open_home()
shell("input", "keyevent", "82")
before = eco_node()
shell("input", "keyevent", "20")  # Down: TV e canais.
shell("input", "keyevent", "22")  # Right: Modo leve.
assert eco_node().get("focused") == "true", "O controle não alcançou o Modo leve"
shell("input", "keyevent", "23")  # OK activates the focused card.
saved = before.attrib["content-desc"]
for _ in range(6):
    saved = eco_node().attrib["content-desc"]
    if saved != before.attrib["content-desc"]: break
    time.sleep(.2)
assert saved != before.attrib["content-desc"], "A preferência não foi alterada"
shell("screencap", "-p", "/sdcard/tcl-upgrade-before.png")
adb("pull", "/sdcard/tcl-upgrade-before.png", str(qa / "before.png"))
home = resolved_home()
assert home == initial_home, "A definição de Home mudou antes da atualização"
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
    "test_environment": "Android TV API 28, 1280x720",
    "home_resolution_unchanged": True, "resolved_home": home,
    "custom_home_selected": custom_home_selected,
    "custom_home_preserved": True if custom_home_selected else None,
    "home_test_limitation": None if custom_home_selected else "TV image kept its persistent stock Home; custom Home selection was not verified",
    "preference_preserved": True,
    "certificate_sha256": (root / "signing/certificate.sha256").read_text().strip()}
(qa / ("TCL-Light-" + version["version_name"] + "-verificacao.json")).write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps(report, indent=2))
