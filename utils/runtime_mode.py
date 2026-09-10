"""
Runtime-mode detection for TG Proxy on Linux.

Distinguishes whether the proxy is currently running as the desktop GUI
(tray app) or as the headless systemd service, so the launch path can inform
the user which mode is active and offer to switch.
"""
from __future__ import annotations

import logging
from typing import Dict, List, Optional

log = logging.getLogger("tg-ws-tray")

# Modes
GUI_MODE = "gui"
SERVICE_MODE = "service"


def _proc_mode(proc) -> Optional[str]:
    """Return the mode for a process if it looks like a TG Proxy instance."""
    try:
        name = (proc.name() or "").lower()
        cmdline = proc.cmdline() or []
    except Exception:
        return None

    joined = " ".join(cmdline).lower()

    # Headless service binary / CLI path (wins over GUI matching).
    if "tg-proxy-service" in name or "tg-proxy-service" in joined:
        return SERVICE_MODE
    if "tg-ws-proxy" in joined:
        return SERVICE_MODE

    # GUI frozen binary: TG-Proxy-1.0.5-Linux (name or full path).
    if "tg-proxy" in name and "linux" in name:
        return GUI_MODE
    if "tg-proxy" in joined and "linux" in joined:
        return GUI_MODE
    # GUI source run: python linux.py
    if "/linux.py" in joined or " linux.py" in joined:
        return GUI_MODE

    return None


def running_modes() -> Dict[str, List[int]]:
    """
    Scan running processes and return a mapping mode -> list of PIDs for any
    TG Proxy instances found (GUI and/or service).
    """
    result: Dict[str, List[int]] = {}
    try:
        import psutil
    except Exception as exc:
        log.warning("psutil unavailable for mode detection: %s", exc)
        return result

    try:
        procs = psutil.process_iter(["pid", "name", "cmdline"])
    except Exception as exc:
        log.warning("Cannot iterate processes: %s", exc)
        return result

    for proc in procs:
        try:
            mode = _proc_mode(proc)
        except Exception:
            continue
        if mode is None:
            continue
        try:
            pid = proc.pid
        except Exception:
            continue
        # avoid counting our own process when we are the GUI
        try:
            if proc.pid == __import__("os").getpid():
                continue
        except Exception:
            pass
        result.setdefault(mode, []).append(pid)
    return result


def any_running() -> bool:
    return bool(running_modes())


def describe_running() -> str:
    """Human-readable summary of which TG Proxy instances are running."""
    modes = running_modes()
    if not modes:
        return ""
    parts = []
    if GUI_MODE in modes:
        parts.append("GUI (трей)")
    if SERVICE_MODE in modes:
        parts.append("служебный (headless/сервис)")
    return ", ".join(parts)


# --- switching helpers -------------------------------------------------------

_SERVICE_UNIT = "tg-proxy"


def _run_cmd(cmd: List[str]) -> int:
    import subprocess

    try:
        return subprocess.call(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except Exception as exc:
        log.warning("Failed to run %s: %s", cmd, exc)
        return -1


def stop_service() -> bool:
    """Stop the headless systemd service (needs root). Returns success."""
    code = _run_cmd(["systemctl", "stop", _SERVICE_UNIT])
    if code != 0:
        code = _run_cmd(["sudo", "systemctl", "stop", _SERVICE_UNIT])
    return code == 0


def start_service() -> bool:
    """Start the headless systemd service (needs root). Returns success."""
    code = _run_cmd(["systemctl", "start", _SERVICE_UNIT])
    if code != 0:
        code = _run_cmd(["sudo", "systemctl", "start", _SERVICE_UNIT])
    return code == 0


def _terminate(pids) -> bool:
    try:
        import psutil
    except Exception:
        return False
    ok = True
    for pid in pids:
        try:
            p = psutil.Process(pid)
            p.terminate()
            p.wait(timeout=3)
        except psutil.NoSuchProcess:
            continue
        except Exception as exc:
            log.warning("Failed to terminate pid %s: %s", pid, exc)
            ok = False
    return ok


def stop_gui() -> bool:
    """Terminate running GUI instances. Returns success."""
    return _terminate(running_modes().get(GUI_MODE, []))
