#!/usr/bin/env python3
"""SHELL_JAR_SETTINGS_REDO — intercept jar 综合设置 panels with TV-native UI.

Targets: playLineOrderSettings / panThreadHubSettings
Rollback: delete this hook file and JarPanSettingsShell.java, rebuild.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
MARKER = "SHELL_JAR_SETTINGS_REDO"

TARGETS = [
    ROOT / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
    ROOT / "app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
]

SNIPPET = """
        // SHELL_JAR_SETTINGS_REDO: 线路排序 / 下载线程 用壳子 TV 原生页，避开 jar Dialog 焦点问题
        try {
            if (com.fongmi.android.tv.ui.dialog.JarPanSettingsShell.isShellPanel(getId())) {
                com.fongmi.android.tv.ui.dialog.JarPanSettingsShell.open(this, getKey(), getId());
                try { finish(); } catch (Throwable ignored) {}
                return;
            }
        } catch (Throwable ignored) {
        }
"""


def patch(path: Path) -> None:
    if not path.is_file():
        print("[mod] WARN missing", path.relative_to(ROOT))
        return
    t = path.read_text(encoding="utf-8")
    if MARKER in t:
        print("[mod] already", path.relative_to(ROOT))
        return
    # insert at start of checkId body after opening brace
    m = re.search(r"(private void checkId\(\)\s*\{)", t)
    if not m:
        print("[mod] ERROR no checkId", path.relative_to(ROOT))
        sys.exit(1)
    # For leanback, checkId may start with if (detailRequested) return;
    insert_at = m.end()
    t = t[:insert_at] + SNIPPET + t[insert_at:]
    path.write_text(t, encoding="utf-8")
    print("[mod] patched checkId", path.relative_to(ROOT))


def main() -> None:
    print("[mod] inject_jar_settings_shell ROOT", ROOT)
    shell = ROOT / "app/src/main/java/com/fongmi/android/tv/ui/dialog/JarPanSettingsShell.java"
    if not shell.is_file():
        print("[mod] ERROR JarPanSettingsShell.java missing — copy mod/ first")
        sys.exit(1)
    for p in TARGETS:
        patch(p)
    print("[mod] inject_jar_settings_shell done")


if __name__ == "__main__":
    main()
