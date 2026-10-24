#!/usr/bin/env python3
"""Verify the published update through the real 0.12.0 UI and Android installer."""
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
qa = root / "dist/internal-update-qa"
qa.mkdir(parents=True, exist_ok=True)


def adb(*args):
    return subprocess.run(["adb", *args], check=True, capture_output=True, text=True).stdout.strip()


def shell(*args):
    return adb("shell", *args)


def snapshot(name="current"):
    shell("uiautomator", "dump", "/sdcard/tcl-internal-update.xml")
    data = shell("cat", "/sdcard/tcl-internal-update.xml")
    (qa / (name + ".xml")).write_text(data)
    return ET.fromstring(data)


def screenshot(name):
    shell("screencap", "-p", "/sdcard/tcl-internal-update.png")
    adb("pull", "/sdcard/tcl-internal-update.png", str(qa / (name + ".png")))


def diagnostics():
    try:
        snapshot("final")
        screenshot("final")
        (qa / "runtime.txt").write_text(shell("logcat", "-d", "-s", "AndroidRuntime:E"))
        (qa / "activities.txt").write_text(shell("dumpsys", "activity", "activities"))
    except Exception:
        pass


atexit.register(diagnostics)


def wait_node(prefix, timeout=40):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        tree = snapshot()
        for node in tree.iter("node"):
            if node.get("content-desc", "").startswith(prefix) and node.get("clickable") == "true":
                return node
        time.sleep(.3)
    raise RuntimeError("A interface não apresentou: " + prefix)


def activate(prefix):
    # All updater actions are native rows and restore focus as their state changes.
    for _ in range(16):
        node = wait_node(prefix)
        if node.get("focused") == "true":
            shell("input", "keyevent", "23")
            return
        shell("input", "keyevent", "20")
    raise RuntimeError("O controle não alcançou: " + prefix)


def resolved_home():
    return shell("cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.MAIN",
                 "-c", "android.intent.category.HOME").splitlines()[-1]


def open_home():
    shell("am", "start", "-W", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME")
    until = time.monotonic() + 30
    while time.monotonic() < until:
        if any(n.get("package") == pkg and n.get("text") == "Garrafão do Norte" for n in snapshot().iter("node")):
            return
        time.sleep(.3)
    raise RuntimeError("A tela inicial não ficou pronta")


# This is a disposable emulator; never downgrade the owner's installed application.
subprocess.run(["adb", "uninstall", pkg], capture_output=True, check=False)
assert "Success" in adb("install", str(root / "dist/baseline/TCL-Light-0.12.0.apk"))
shell("settings", "put", "system", "system_locales", "pt-BR")
assert "Success" in shell("cmd", "package", "set-home-activity", "--user", "0", pkg + "/.MainActivity")
stock = resolved_home().split("/")[0]
if stock == "com.google.android.tvlauncher":
    shell("pm", "disable-user", "--user", "0", stock)
    atexit.unregister(diagnostics)
    atexit.register(lambda: shell("pm", "enable", "--user", "0", stock))
    atexit.register(diagnostics)
home = resolved_home()
assert home.startswith(pkg + "/")
shell("appops", "set", pkg, "REQUEST_INSTALL_PACKAGES", "allow")
open_home()
shell("input", "keyevent", "82")
before = wait_node("Modo leve.").get("content-desc")
shell("input", "keyevent", "20")
shell("input", "keyevent", "22")
assert wait_node("Modo leve.").get("focused") == "true"
shell("input", "keyevent", "23")
saved = wait_node("Modo leve.").get("content-desc")
assert saved != before
shell("input", "keyevent", "20")
activate("Atualizar TCL Light.")
activate("Verificar atualizações.")
activate("Baixar versão " + version["version_name"] + ".")
wait_node("Instalar atualização.", timeout=180)
snapshot("download-verified")
screenshot("download-verified")
activate("Instalar atualização.")

button = None
until = time.monotonic() + 45
while time.monotonic() < until:
    tree = snapshot("installer")
    if any("packageinstaller" in n.get("package", "") for n in tree.iter("node")):
        assert any(n.get("text") == "TCL Light" for n in tree.iter("node")), "Instalador recebeu outro aplicativo"
        for node in tree.iter("node"):
            label = node.get("text", "").strip().casefold()
            if label in {"instalar", "install", "atualizar", "update"} and node.get("enabled") == "true":
                button = node
                break
    if button is not None:
        break
    time.sleep(.3)
assert button is not None, "O Android não apresentou a confirmação de instalação"
screenshot("installer")
coords = [int(x) for x in re.findall(r"\d+", button.get("bounds", ""))]
assert len(coords) == 4
shell("input", "tap", str((coords[0] + coords[2]) // 2), str((coords[1] + coords[3]) // 2))
until = time.monotonic() + 90
while time.monotonic() < until:
    state = shell("dumpsys", "package", pkg)
    if re.search(r"versionCode=" + str(version["version_code"]) + r"\b", state):
        break
    time.sleep(.5)
else:
    raise RuntimeError("O instalador não concluiu a atualização interna")
assert resolved_home() == home, "A atualização interna perdeu o launcher padrão"
open_home()
shell("input", "keyevent", "82")
assert wait_node("Modo leve.").get("content-desc") == saved, "A atualização interna perdeu a configuração"
snapshot("updated")
screenshot("updated")
report = {"baseline_version": "0.12.0", "version": version["version_name"], "version_code": version["version_code"],
          "method": "Public GitHub beta feed, application download, signature check, native installer confirmation",
          "result": "Success", "custom_home_preserved": True, "preference_preserved": True,
          "test_environment": "Android TV API 28, 1280x720", "certificate_sha256":
          (root / "signing/certificate.sha256").read_text().strip()}
(qa / ("TCL-Light-" + version["version_name"] + "-atualizacao-interna.json")).write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps(report, indent=2))
