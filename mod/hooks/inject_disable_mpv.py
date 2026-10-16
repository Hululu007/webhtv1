#!/usr/bin/env python3
"""Disable MPV completely (UI + engine). Run against repo ROOT.

python3 mod/hooks/inject_disable_mpv.py "$ROOT"
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".")


def patch_player_setting(path: Path) -> None:
    if not path.exists():
        print("[mod] skip missing", path)
        return
    t = path.read_text(encoding="utf-8")
    orig = t

    t = re.sub(
        r"public static final int\[\] KERNEL_ORDER = \{EXO, IJK, MPV, SYSTEM\};[^\n]*",
        "public static final int[] KERNEL_ORDER = {EXO, IJK, SYSTEM}; // MPV removed",
        t,
    )

    t = re.sub(
        r"public static boolean isPlayer\(int player\) \{\s*"
        r"return player == EXO \|\| player == IJK \|\| player == SYSTEM \|\| player == MPV;\s*\}",
        "public static boolean isPlayer(int player) {\n"
        "        return player == EXO || player == IJK || player == SYSTEM; // MPV disabled\n"
        "    }",
        t,
        count=1,
    )

    if "/* mpv-put-block */" not in t:
        t = re.sub(
            r"public static void putPlayer\(int player\) \{\s*"
            r"Prefers\.put\(\"player\", sanitizePlayer\(player\)\);\s*\}",
            "public static void putPlayer(int player) {\n"
            "        /* mpv-put-block */\n"
            "        if (player == MPV) player = EXO;\n"
            "        Prefers.put(\"player\", sanitizePlayer(player));\n"
            "    }",
            t,
            count=1,
        )

    if "/* mpv-disabled-guard */" not in t:
        t = t.replace(
            "public static int getPlayer() {\n"
            "        int player = Prefers.getInt(\"player\", EXO);\n"
            "        if (isPlayer(player)) return player;\n"
            "        putPlayer(EXO);\n"
            "        return EXO;\n"
            "    }",
            "public static int getPlayer() {\n"
            "        int player = Prefers.getInt(\"player\", EXO);\n"
            "        /* mpv-disabled-guard */\n"
            "        if (player == MPV) {\n"
            "            putPlayer(EXO);\n"
            "            return EXO;\n"
            "        }\n"
            "        if (isPlayer(player)) return player;\n"
            "        putPlayer(EXO);\n"
            "        return EXO;\n"
            "    }",
        )

    if "/* mpv-active-block */" not in t:
        t = re.sub(
            r"public static void putActivePlayer\(int player\) \{\s*"
            r"activePlayer = isPlayer\(player\) \? player : NONE;\s*\}",
            "public static void putActivePlayer(int player) {\n"
            "        /* mpv-active-block */\n"
            "        if (player == MPV) player = EXO;\n"
            "        activePlayer = isPlayer(player) ? player : NONE;\n"
            "    }",
            t,
            count=1,
        )

    if t != orig:
        path.write_text(t, encoding="utf-8")
        print("[mod] patched", path)
    else:
        print("[mod] no change", path)


def patch_player_manager(path: Path) -> None:
    if not path.exists():
        print("[mod] skip missing", path)
        return
    t = path.read_text(encoding="utf-8")
    orig = t
    if "if (type == PlayerSetting.MPV) type = PlayerSetting.EXO;" not in t:
        t = re.sub(
            r"(private PlayerEngine buildEngine\(int type, int decode\) \{\n)",
            r"\1        if (type == PlayerSetting.MPV) type = PlayerSetting.EXO;\n",
            t,
            count=1,
        )
    t = t.replace(
        "case PlayerSetting.MPV -> new MpvPlayerEngine(decode, lutAllowed, listener, this::onMpvVideoSizeProbed);",
        "case PlayerSetting.MPV -> new ExoPlayerEngine(decode, listener, mediaSignals, mediaClock); // MPV disabled",
    )
    if t != orig:
        path.write_text(t, encoding="utf-8")
        print("[mod] patched", path)
    else:
        print("[mod] no change", path)


def force_mpv_rows_gone(path: Path) -> None:
    if not path.exists():
        print("[mod] skip missing", path)
        return
    t = path.read_text(encoding="utf-8")
    orig = t
    t = re.sub(
        r"private void setMpvRows\(\) \{\s*"
        r"boolean visible = PlayerSetting\.getPlayer\(\) == PlayerSetting\.MPV;",
        "private void setMpvRows() {\n"
        "        boolean visible = false; // MPV disabled",
        t,
        count=1,
    )
    if "/* mpv-ui-force-gone */" not in t and "setMpvRows();" in t:
        t = t.replace(
            "setMpvRows();",
            "setMpvRows();\n"
            "        /* mpv-ui-force-gone */\n"
            "        try { mBinding.mpvConfig.setVisibility(android.view.View.GONE); } catch (Throwable ignored) {}\n"
            "        try { mBinding.mpvRender.setVisibility(android.view.View.GONE); } catch (Throwable ignored) {}\n"
            "        try { mBinding.blurayMenu.setVisibility(android.view.View.GONE); } catch (Throwable ignored) {}",
            1,
        )
    if t != orig:
        path.write_text(t, encoding="utf-8")
        print("[mod] patched UI", path)
    else:
        print("[mod] no change", path)


def main() -> None:
    print("[mod] inject_disable_mpv ROOT=", ROOT.resolve())
    patch_player_setting(ROOT / "app/src/main/java/com/fongmi/android/tv/setting/PlayerSetting.java")
    patch_player_manager(ROOT / "app/src/main/java/com/fongmi/android/tv/player/PlayerManager.java")
    for rel in (
        "app/src/mobile/java/com/fongmi/android/tv/ui/fragment/SettingPlayerFragment.java",
        "app/src/leanback/java/com/fongmi/android/tv/ui/fragment/SettingPlayerFragment.java",
        "app/src/leanback/java/com/fongmi/android/tv/ui/activity/SettingPlayerActivity.java",
        "app/src/mobile/java/com/fongmi/android/tv/ui/activity/SettingPlayerActivity.java",
    ):
        force_mpv_rows_gone(ROOT / rel)
    try:
        (ROOT / "mod_mpv_disabled.marker").write_text("mpv disabled v2\n", encoding="utf-8")
    except Exception:
        pass
    print("[mod] inject_disable_mpv done")


if __name__ == "__main__":
    main()
