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
    request = ("-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME")
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

# Reproduce the owner's verified setup: the factory Google launcher stays installed,
# disabled only for user 0 inside this disposable emulator. Restore it after diagnostics.
baseline = root / "dist/baseline/TCL-Light-0.11.0.apk"
assert "Success" in adb("install", str(baseline))
home_request = shell("cmd", "package", "set-home-activity", "--user", "0", pkg + "/.MainActivity")
(qa / "home-request.txt").write_text(home_request)
assert "Success" in home_request
stock_home = resolved_home().split("/")[0]
if stock_home == "com.google.android.tvlauncher":
    disabled = shell("pm", "disable-user", "--user", "0", stock_home)
    assert "disabled-user" in disabled
    # atexit runs in reverse registration order: capture diagnostics before restoring Home.
    atexit.unregister(diagnostics)
    atexit.register(lambda: shell("pm", "enable", "--user", "0", stock_home))
    atexit.register(diagnostics)
initial_home = resolved_home()
assert initial_home.startswith(pkg + "/"), "O emulador não selecionou o launcher alternativo"
open_home()
shell("input", "keyevent", "82")
before = eco_node()
shell("input", "keyevent", "20")
shell("input", "keyevent", "22")
assert eco_node().get("focused") == "true", "O controle não alcançou o Modo leve"
shell("input", "keyevent", "23")
saved = before.attrib["content-desc"]
for _ in range(6):
    saved = eco_node().attrib["content-desc"]
    if saved != before.attrib["content-desc"]: break
    time.sleep(.2)
assert saved != before.attrib["content-desc"], "A preferência não foi alterada"
shell("screencap", "-p", "/sdcard/tcl-upgrade-before.png")
adb("pull", "/sdcard/tcl-upgrade-before.png", str(qa / "before.png"))
home = resolved_home()
updates = []
for name, code in [("TCL-Light-" + version["version_name"] + ".apk", version["version_code"]),
                   ("upgrade-probe.apk", version["upgrade_probe_code"])]:
    result = adb("install", "-r", str(root / "dist/signed" / name))
    assert "Success" in result, "O Android recusou a atualização sobre a versão instalada"
    assert resolved_home() == home, "A atualização perdeu a escolha da tela inicial"
    # Actual HOME dispatch, not an explicit component launch.
    open_home()
    shell("input", "keyevent", "82")
    assert eco_node().attrib["content-desc"] == saved, "A atualização perdeu a preferência salva"
    shell("screencap", "-p", "/sdcard/tcl-upgrade-after.png")
    adb("pull", "/sdcard/tcl-upgrade-after.png", str(qa / ("after-code-" + str(code) + ".png")))
    updates.append({"target_code": code, "result": "Success", "home_preserved": True, "preference_preserved": True})
report = {"version": version["version_name"], "baseline_version": "0.11.0", "baseline_code": 11,
    "installed_code": version["version_code"], "internal_upgrade_code": version["upgrade_probe_code"],
    "updates": updates, "update_result": "Success", "test_environment": "Android TV API 28, 1280x720",
    "stock_launcher_disabled_for_test": stock_home == "com.google.android.tvlauncher",
    "home_resolution_unchanged": True, "resolved_home": home, "custom_home_selected": True,
    "custom_home_preserved": True, "preference_preserved": True,
    "certificate_sha256": (root / "signing/certificate.sha256").read_text().strip()}
(qa / ("TCL-Light-" + version["version_name"] + "-verificacao.json")).write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps(report, indent=2))
