#!/usr/bin/env python3
"""Disable MPV kernel: never select/create MPV, always fall back to EXO.

Phase-1 removal for 墨壳:
- PlayerSetting: drop MPV from isPlayer / KERNEL_ORDER
- PlayerManager.buildEngine: MPV type redirected to EXO before switch
Does NOT delete native libs yet (separate packaging step).
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

    t = t.replace(
        "public static final int[] KERNEL_ORDER = {EXO, IJK, MPV, SYSTEM};",
        "public static final int[] KERNEL_ORDER = {EXO, IJK, SYSTEM}; // MPV removed",
    )
    # isPlayer: exclude MPV
    t = re.sub(
        r"public static boolean isPlayer\(int player\) \{\s*"
        r"return player == EXO \|\| player == IJK \|\| player == SYSTEM \|\| player == MPV;\s*\}",
        "public static boolean isPlayer(int player) {\n"
        "        return player == EXO || player == IJK || player == SYSTEM; // MPV disabled\n"
        "    }",
        t,
        count=1,
    )
    # sanitizePlayer already maps invalid -> EXO via isPlayer
    # Force getPlayer to never return MPV even if old pref
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

    # Redirect at the top of buildEngine — covers all call sites
    old = """    private PlayerEngine buildEngine(int type, int decode) {
        // Every engine build discards the AudioSink that carried the PCM tap, so the next
        // one deserves a fresh attempt budget. rebuildAudioPipeline() itself does not come
        // through here, which is what keeps the cap meaningful.
        adAudioPipelineRebuilds = 0;
        if (type != PlayerSetting.EXO) {
"""
    new = """    private PlayerEngine buildEngine(int type, int decode) {
        // MPV 已禁用：历史设置/回退若仍指向 MPV，一律改走 EXO
        if (type == PlayerSetting.MPV) type = PlayerSetting.EXO;
        // Every engine build discards the AudioSink that carried the PCM tap, so the next
        // one deserves a fresh attempt budget. rebuildAudioPipeline() itself does not come
        // through here, which is what keeps the cap meaningful.
        adAudioPipelineRebuilds = 0;
        if (type != PlayerSetting.EXO) {
"""
    if old in t:
        t = t.replace(old, new, 1)
    elif "if (type == PlayerSetting.MPV) type = PlayerSetting.EXO;" not in t:
        # fallback: insert after method signature
        t = re.sub(
            r"(private PlayerEngine buildEngine\(int type, int decode\) \{\n)",
            r"\1        if (type == PlayerSetting.MPV) type = PlayerSetting.EXO;\n",
            t,
            count=1,
        )

    # Also neutralize switch arm so even if type slips through, no MpvPlayerEngine
    t = t.replace(
        "case PlayerSetting.MPV -> new MpvPlayerEngine(decode, lutAllowed, listener, this::onMpvVideoSizeProbed);",
        "case PlayerSetting.MPV -> new ExoPlayerEngine(decode, listener, mediaSignals, mediaClock); // MPV disabled",
    )

    if t != orig:
        path.write_text(t, encoding="utf-8")
        print("[mod] patched", path)
    else:
        print("[mod] no change", path)


def patch_setting_player_ui(path: Path) -> None:
    """Hide pure-MPV settings rows when present (mobile/leanback SettingPlayer*)."""
    if not path.exists():
        return
    t = path.read_text(encoding="utf-8")
    orig = t
    # Force mpv-only rows GONE when methods exist
    for name in ("mpvConf", "mpvVulkan", "mpvGpuNext"):
        # mBinding.mpvConf.setVisibility(...)
        t = re.sub(
            rf"(mBinding\.{name}\.setVisibility\()[^)]+(\))",
            rf"\1View.GONE\2 /* MPV disabled */",
            t,
        )
    if t != orig:
        path.write_text(t, encoding="utf-8")
        print("[mod] patched UI", path)


def main() -> None:
    patch_player_setting(ROOT / "app/src/main/java/com/fongmi/android/tv/setting/PlayerSetting.java")
    patch_player_manager(ROOT / "app/src/main/java/com/fongmi/android/tv/player/PlayerManager.java")
    for rel in (
        "app/src/mobile/java/com/fongmi/android/tv/ui/fragment/SettingPlayerFragment.java",
        "app/src/leanback/java/com/fongmi/android/tv/ui/fragment/SettingPlayerFragment.java",
        "app/src/mobile/java/com/fongmi/android/tv/ui/activity/SettingPlayerActivity.java",
        "app/src/leanback/java/com/fongmi/android/tv/ui/activity/SettingPlayerActivity.java",
    ):
        patch_setting_player_ui(ROOT / rel)
    print("[mod] inject_disable_mpv done")


if __name__ == "__main__":
    main()
