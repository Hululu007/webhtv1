#!/usr/bin/env python3
"""Movie: hide OP/ED. Live: honor PlayerButtonSetting hide for scale & play_params."""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()


def patch_video() -> None:
    path = ROOT / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java"
    if not path.is_file():
        print("[mod] WARN VideoActivity missing")
        return
    t = path.read_text(encoding="utf-8")
    if "OPENING_MOVIE_HIDE" in t:
        print("[mod] VideoActivity OP/ED movie hide already")
        return

    # After EPISODES setVisibleIfAllowed in setEpisodeAdapter
    needle = (
        "PlayerButtonSetting.setVisibleIfAllowed(PlayerButtonSetting.EPISODES, "
        "mBinding.control.action.episodes, items.size() >= 2);"
    )
    insert = (
        needle
        + "\n        // OPENING_MOVIE_HIDE: 电影(集数<2)隐藏片头片尾，连续剧保留\n"
        + "        try {\n"
        + "            PlayerButtonSetting.setVisibleIfAllowed(PlayerButtonSetting.OPENING, "
        "mBinding.control.action.opening, items.size() >= 2);\n"
        + "            PlayerButtonSetting.setVisibleIfAllowed(PlayerButtonSetting.ENDING, "
        "mBinding.control.action.ending, items.size() >= 2);\n"
        + "        } catch (Throwable ignored) {}\n"
    )
    if needle in t:
        t = t.replace(needle, insert, 1)
        path.write_text(t, encoding="utf-8")
        print("[mod] VideoActivity movie OP/ED hide")
        return

    # Fallback: inject into applyActionButtonVisibility / forceHideConfiguredActionButtons
    alt = "private void forceHideConfiguredActionButtons() {"
    if alt in t:
        block = (
            alt
            + "\n        try {\n"
            + "            int ep = 0;\n"
            + "            try {\n"
            + "                if (getFlag() != null && getFlag().getEpisodes() != null) "
            "ep = getFlag().getEpisodes().size();\n"
            + "            } catch (Throwable ignored) {}\n"
            + "            // OPENING_MOVIE_HIDE\n"
            + "            PlayerButtonSetting.setVisibleIfAllowed(PlayerButtonSetting.OPENING, "
            "mBinding.control.action.opening, ep >= 2);\n"
            + "            PlayerButtonSetting.setVisibleIfAllowed(PlayerButtonSetting.ENDING, "
            "mBinding.control.action.ending, ep >= 2);\n"
            + "        } catch (Throwable ignored) {}\n"
        )
        t = t.replace(alt, block, 1)
        path.write_text(t, encoding="utf-8")
        print("[mod] VideoActivity OP/ED via forceHideConfigured")
        return

    print("[mod] ERROR cannot patch VideoActivity OP/ED")
    sys.exit(1)


def patch_live() -> None:
    path = ROOT / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/LiveActivity.java"
    if not path.is_file():
        print("[mod] WARN LiveActivity missing")
        return
    t = path.read_text(encoding="utf-8")
    if "applyLiveButtonVisibility" in t:
        print("[mod] LiveActivity button visibility already")
        return

    helper = """
    private void applyLiveButtonVisibility() {
        try {
            PlayerButtonSetting.setVisibleIfAllowed(
                    PlayerButtonSetting.SCALE, mBinding.control.action.scale, true);
            PlayerButtonSetting.setVisibleIfAllowed(
                    PlayerButtonSetting.PLAY_PARAMS, mBinding.control.action.playParams, true);
        } catch (Throwable ignored) {
        }
    }
"""
    # import if needed
    if "import com.fongmi.android.tv.setting.PlayerButtonSetting;" not in t:
        t = t.replace(
            "import com.fongmi.android.tv.setting.PlayerSetting;",
            "import com.fongmi.android.tv.setting.PlayerButtonSetting;\n"
            "import com.fongmi.android.tv.setting.PlayerSetting;",
            1,
        )
        if "import com.fongmi.android.tv.setting.PlayerButtonSetting;" not in t:
            t = t.replace(
                "package com.fongmi.android.tv.ui.activity;",
                "package com.fongmi.android.tv.ui.activity;\n\n"
                "import com.fongmi.android.tv.setting.PlayerButtonSetting;",
                1,
            )

    last = t.rfind("\n}")
    t = t[:last] + helper + t[last:]

    # call from showControl
    if "private void showControl" in t:
        t2, n = re.subn(
            r"(private void showControl\([^\)]*\) \{\n)",
            r"\1        applyLiveButtonVisibility();\n",
            t,
            count=1,
        )
        if n:
            t = t2
        else:
            t = re.sub(
                r"(void showControl\([^\)]*\) \{\n)",
                r"\1        applyLiveButtonVisibility();\n",
                t,
                count=1,
            )
    # call after setScale initial in initView-ish: after setScale(LiveSetting.getScale())
    if "setScale(LiveSetting.getScale());" in t:
        t = t.replace(
            "setScale(LiveSetting.getScale());",
            "setScale(LiveSetting.getScale());\n        applyLiveButtonVisibility();",
            1,
        )
    elif "super.onResume();" in t:
        t = t.replace(
            "super.onResume();",
            "super.onResume();\n        applyLiveButtonVisibility();",
            1,
        )

    path.write_text(t, encoding="utf-8")
    print("[mod] LiveActivity applyLiveButtonVisibility")


def main() -> None:
    print("[mod] inject_player_buttons_fix ROOT", ROOT)
    patch_video()
    patch_live()
    print("[mod] inject_player_buttons_fix done")


if __name__ == "__main__":
    main()
