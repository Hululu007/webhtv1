#!/usr/bin/env python3
"""SHELL_JAR_SETTINGS_REDO v2 — TV-native 线路排序/下载线程/账号登录 + SiteApi intercept."""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
MARKER = "SHELL_JAR_SETTINGS_REDO"

CHECKID = """
        // SHELL_JAR_SETTINGS_REDO: 壳子原生 TV 面板
        try {
            if (com.fongmi.android.tv.ui.dialog.JarPanSettingsShell.isShellPanel(getId())) {
                com.fongmi.android.tv.ui.dialog.JarPanSettingsShell.open(this, getKey(), getId());
                try { finish(); } catch (Throwable ignored) {}
                return;
            }
        } catch (Throwable ignored) {
        }
"""


def patch_video(path: Path) -> None:
    if not path.is_file():
        print("[mod] WARN missing", path)
        return
    t = path.read_text(encoding="utf-8")
    if MARKER in t and "JarPanSettingsShell.open" in t:
        print("[mod] VideoActivity already", path.relative_to(ROOT))
        return
    m = re.search(r"(private void checkId\(\)\s*\{)", t)
    if not m:
        print("[mod] ERROR no checkId", path)
        sys.exit(1)
    t = t[: m.end()] + CHECKID + t[m.end() :]
    path.write_text(t, encoding="utf-8")
    print("[mod] VideoActivity", path.relative_to(ROOT))


def patch_site_api(path: Path) -> None:
    if not path.is_file():
        print("[mod] WARN no SiteApi", path)
        return
    t = path.read_text(encoding="utf-8")
    if MARKER in t:
        print("[mod] SiteApi already")
        return
    # inject at start of detailContent method body
    m = re.search(
        r"public static Result detailContent\(@NonNull String key, @NonNull String id\) throws Exception \{",
        t,
    )
    if not m:
        m = re.search(
            r"public static Result detailContent\(String key, String id\) throws Exception \{",
            t,
        )
    if not m:
        print("[mod] ERROR detailContent not found")
        sys.exit(1)
    insert = """
        // SHELL_JAR_SETTINGS_REDO
        try {
            if (com.fongmi.android.tv.ui.dialog.JarPanSettingsShell.isShellPanel(id)) {
                com.fongmi.android.tv.ui.dialog.JarPanSettingsShell.openPost(key, id);
                return com.fongmi.android.tv.bean.Result.empty();
            }
        } catch (Throwable ignored) {
        }
"""
    t = t[: m.end()] + insert + t[m.end() :]
    path.write_text(t, encoding="utf-8")
    print("[mod] SiteApi detailContent intercept OK")


def main() -> None:
    print("[mod] inject_jar_settings_shell ROOT", ROOT)
    shell = ROOT / "app/src/main/java/com/fongmi/android/tv/ui/dialog/JarPanSettingsShell.java"
    if not shell.is_file():
        print("[mod] ERROR missing JarPanSettingsShell.java")
        sys.exit(1)
    for flavor in ("leanback", "mobile"):
        patch_video(ROOT / f"app/src/{flavor}/java/com/fongmi/android/tv/ui/activity/VideoActivity.java")
    patch_site_api(ROOT / "app/src/main/java/com/fongmi/android/tv/api/SiteApi.java")
    print("[mod] inject_jar_settings_shell done")


if __name__ == "__main__":
    main()
