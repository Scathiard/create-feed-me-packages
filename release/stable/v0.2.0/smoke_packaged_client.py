"""Packaged-client smoke: launch runClient -PfmpPackaged, wait until the renderer is ready,
optionally drive a quick-play world, then close the real window gracefully (WM_CLOSE) and report."""
import ctypes
import ctypes.wintypes as wt
import os
import re
import subprocess
import sys
import threading
import time
from pathlib import Path

ROOT = r"D:\claude_sandbox\Create-Feed Me Packages!"
user32 = ctypes.WinDLL("user32", use_last_error=True)
WM_CLOSE = 0x0010


def window_pids():
    """Map visible top-level window handles to their process ids."""
    result = []

    @ctypes.WINFUNCTYPE(wt.BOOL, wt.HWND, wt.LPARAM)
    def enum_proc(hwnd, lparam):
        if user32.IsWindowVisible(hwnd):
            pid = wt.DWORD()
            user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
            length = user32.GetWindowTextLengthW(hwnd)
            buf = ctypes.create_unicode_buffer(length + 1)
            user32.GetWindowTextW(hwnd, buf, length + 1)
            result.append((hwnd, pid.value, buf.value))
        return True

    user32.EnumWindows(enum_proc, 0)
    return result


def main():
    tag = sys.argv[1] if len(sys.argv) > 1 else "packaged-client"
    extra = sys.argv[2:]
    log = Path(__file__).parent / f"{tag}.log"
    env = dict(os.environ)
    env["GRADLE_OPTS"] = "-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890"
    proc = subprocess.Popen(
        ["cmd", "/c", "gradlew.bat", "runClient", "--offline", "--no-daemon",
         "--no-configuration-cache", "--console=plain", *extra],
        cwd=ROOT, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, encoding="utf-8", errors="replace", bufsize=1, env=env)

    state = {"packaged": None, "ready": False, "started": time.time(), "closed": False}
    lines = []

    def reader():
        for line in proc.stdout:
            lines.append(line.rstrip())
            if "FMP_PACKAGED_SOURCE" in line:
                m = re.search(r"jar=(\S+)", line)
                state["packaged"] = m.group(1) if m else "?"
            if "Created: 1024x512x0 minecraft:textures/atlas/gui.png-atlas" in line or "Reloading ResourceManager" in line:
                state["ready"] = True

    thread = threading.Thread(target=reader, daemon=True)
    thread.start()

    deadline = time.time() + 420
    target_pids = []
    while time.time() < deadline:
        if state["ready"] and not target_pids:
            time.sleep(6)  # let the title screen finish drawing
            for hwnd, pid, title in window_pids():
                if "Minecraft" in title or "minecraft" in title.lower():
                    target_pids.append((hwnd, pid, title))
            if target_pids:
                print("WINDOW_FOUND", [(t, p) for _, p, t in target_pids], flush=True)
        if target_pids and not state["closed"]:
            time.sleep(8)  # smoke window: let it settle, then close gracefully
            for hwnd, pid, title in target_pids:
                user32.PostMessageW(hwnd, WM_CLOSE, 0, 0)
            state["closed"] = True
            print("WM_CLOSE_SENT", [p for _, p, _ in target_pids], flush=True)
        if proc.poll() is not None:
            break
        time.sleep(1)

    try:
        proc.wait(timeout=180)
    except subprocess.TimeoutExpired:
        print("TIMEOUT - killing")
        proc.kill()
    with open(log, "w", encoding="utf-8") as out:
        out.write("\n".join(lines) + "\n")
    print("EXIT", proc.returncode)
    print("PACKAGED_JAR", state["packaged"])
    print("RENDER_READY", state["ready"])
    print("WINDOW_CLOSED", state["closed"])
    print("LOG", log.resolve())


if __name__ == "__main__":
    main()
