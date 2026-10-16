#!/usr/bin/env python3
"""
全局禁用 MPV，统一落到 EXO。

用法（在仓库根目录，apply.sh 复制完 mod 文件之后、Gradle 之前）:
  python3 mod/hooks/inject_disable_mpv.py "$ROOT"

设计原则:
  - 基于 Silent1566/webhtv（鱼壳）当前真实源码字符串做精确替换
  - 已打过补丁则跳过该项（可重复执行）
  - 任一项既未生效、又匹配失败 → 非 0 退出，让 CI 失败
  - 结尾 verify() 再次扫描关键标记，失败同样非 0 退出
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
ERRORS: list[str] = []
OK: list[str] = []
SKIP: list[str] = []


def rel(p: Path) -> str:
    try:
        return str(p.relative_to(ROOT))
    except ValueError:
        return str(p)


def require_file(path: Path) -> str | None:
    if not path.is_file():
        ERRORS.append(f"MISSING file: {rel(path)}")
        return None
    return path.read_text(encoding="utf-8")


def save(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8")


def replace_once(
    name: str,
    path: Path,
    text: str,
    old: str,
    new: str,
    *,
    already_markers: list[str] | None = None,
) -> str:
    """精确替换一次。已含 marker 则 skip；old 不在且未 patch → error。"""
    markers = already_markers or []
    if any(m in text for m in markers):
        SKIP.append(f"{name}: already applied ({rel(path)})")
        return text
    if old not in text:
        ERRORS.append(
            f"{name}: pattern not found in {rel(path)}\n"
            f"  expected snippet starts with: {old[:80]!r}..."
        )
        return text
    text2 = text.replace(old, new, 1)
    if text2 == text:
        ERRORS.append(f"{name}: replace produced no change ({rel(path)})")
        return text
    OK.append(f"{name}: OK ({rel(path)})")
    return text2


def regex_replace_once(
    name: str,
    path: Path,
    text: str,
    pattern: str,
    repl: str,
    *,
    already_markers: list[str] | None = None,
    flags: int = re.MULTILINE,
) -> str:
    markers = already_markers or []
    if any(m in text for m in markers):
        SKIP.append(f"{name}: already applied ({rel(path)})")
        return text
    rx = re.compile(pattern, flags)
    m = rx.search(text)
    if not m:
        ERRORS.append(f"{name}: regex not found in {rel(path)}\n  pattern: {pattern[:100]}")
        return text
    text2, n = rx.subn(repl, text, count=1)
    if n != 1:
        ERRORS.append(f"{name}: expected 1 substitution, got {n} ({rel(path)})")
        return text
    OK.append(f"{name}: OK ({rel(path)})")
    return text2


# ---------------------------------------------------------------------------
# PlayerSetting.java
# ---------------------------------------------------------------------------

def patch_player_setting() -> None:
    path = ROOT / "app/src/main/java/com/fongmi/android/tv/setting/PlayerSetting.java"
    text = require_file(path)
    if text is None:
        return

    text = replace_once(
        "KERNEL_ORDER",
        path,
        text,
        "public static final int[] KERNEL_ORDER = {EXO, IJK, MPV, SYSTEM};",
        "public static final int[] KERNEL_ORDER = {EXO, IJK, SYSTEM}; // MPV_DISABLED",
        already_markers=["KERNEL_ORDER = {EXO, IJK, SYSTEM}"],
    )

    text = replace_once(
        "isPlayer",
        path,
        text,
        "public static boolean isPlayer(int player) {\n"
        "        return player == EXO || player == IJK || player == SYSTEM || player == MPV;\n"
        "    }",
        "public static boolean isPlayer(int player) {\n"
        "        // MPV_DISABLED: MPV is no longer a selectable kernel\n"
        "        return player == EXO || player == IJK || player == SYSTEM;\n"
        "    }",
        already_markers=["MPV_DISABLED: MPV is no longer a selectable kernel"],
    )

    text = replace_once(
        "getPlayer",
        path,
        text,
        "public static int getPlayer() {\n"
        "        int player = Prefers.getInt(\"player\", EXO);\n"
        "        if (isPlayer(player)) return player;\n"
        "        putPlayer(EXO);\n"
        "        return EXO;\n"
        "    }",
        "public static int getPlayer() {\n"
        "        int player = Prefers.getInt(\"player\", EXO);\n"
        "        // MPV_DISABLED: migrate legacy preference\n"
        "        if (player == MPV) {\n"
        "            putPlayer(EXO);\n"
        "            return EXO;\n"
        "        }\n"
        "        if (isPlayer(player)) return player;\n"
        "        putPlayer(EXO);\n"
        "        return EXO;\n"
        "    }",
        already_markers=["MPV_DISABLED: migrate legacy preference"],
    )

    text = replace_once(
        "putPlayer",
        path,
        text,
        "public static void putPlayer(int player) {\n"
        "        Prefers.put(\"player\", sanitizePlayer(player));\n"
        "    }",
        "public static void putPlayer(int player) {\n"
        "        // MPV_DISABLED: never persist MPV\n"
        "        if (player == MPV) player = EXO;\n"
        "        Prefers.put(\"player\", sanitizePlayer(player));\n"
        "    }",
        already_markers=["MPV_DISABLED: never persist MPV"],
    )

    text = replace_once(
        "getActivePlayer",
        path,
        text,
        "public static int getActivePlayer() {\n"
        "        int player = activePlayer;\n"
        "        return isPlayer(player) ? player : getPlayer();\n"
        "    }",
        "public static int getActivePlayer() {\n"
        "        int player = activePlayer;\n"
        "        // MPV_DISABLED: stale session kernel\n"
        "        if (player == MPV) {\n"
        "            activePlayer = EXO;\n"
        "            return getPlayer();\n"
        "        }\n"
        "        return isPlayer(player) ? player : getPlayer();\n"
        "    }",
        already_markers=["MPV_DISABLED: stale session kernel"],
    )

    text = replace_once(
        "putActivePlayer",
        path,
        text,
        "public static void putActivePlayer(int player) {\n"
        "        activePlayer = isPlayer(player) ? player : NONE;\n"
        "    }",
        "public static void putActivePlayer(int player) {\n"
        "        // MPV_DISABLED: never activate MPV\n"
        "        if (player == MPV) player = EXO;\n"
        "        activePlayer = isPlayer(player) ? player : NONE;\n"
        "    }",
        already_markers=["MPV_DISABLED: never activate MPV"],
    )

    # sanitizePlayer already relies on isPlayer → MPV maps to EXO after isPlayer patch
    save(path, text)


# ---------------------------------------------------------------------------
# PlayerManager.java — only construction site for MpvPlayerEngine
# ---------------------------------------------------------------------------

def patch_player_manager() -> None:
    path = ROOT / "app/src/main/java/com/fongmi/android/tv/player/PlayerManager.java"
    text = require_file(path)
    if text is None:
        return

    # Guard at top of buildEngine (covers all call sites)
    text = replace_once(
        "buildEngine_guard",
        path,
        text,
        "    private PlayerEngine buildEngine(int type, int decode) {\n"
        "        // Every engine build discards the AudioSink that carried the PCM tap, so the next\n"
        "        // one deserves a fresh attempt budget. rebuildAudioPipeline() itself does not come\n"
        "        // through here, which is what keeps the cap meaningful.\n"
        "        adAudioPipelineRebuilds = 0;\n",
        "    private PlayerEngine buildEngine(int type, int decode) {\n"
        "        // MPV_DISABLED: any request for MPV becomes EXO before engine construction\n"
        "        if (type == PlayerSetting.MPV) type = PlayerSetting.EXO;\n"
        "        // Every engine build discards the AudioSink that carried the PCM tap, so the next\n"
        "        // one deserves a fresh attempt budget. rebuildAudioPipeline() itself does not come\n"
        "        // through here, which is what keeps the cap meaningful.\n"
        "        adAudioPipelineRebuilds = 0;\n",
        already_markers=["MPV_DISABLED: any request for MPV becomes EXO"],
    )

    text = replace_once(
        "buildEngine_switch_arm",
        path,
        text,
        "case PlayerSetting.MPV -> new MpvPlayerEngine(decode, lutAllowed, listener, this::onMpvVideoSizeProbed);",
        "case PlayerSetting.MPV -> new ExoPlayerEngine(decode, listener, mediaSignals, mediaClock); // MPV_DISABLED",
        already_markers=["case PlayerSetting.MPV -> new ExoPlayerEngine"],
    )

    # Safety: no remaining `new MpvPlayerEngine` in this file after patch
    save(path, text)
    text2 = path.read_text(encoding="utf-8")
    if "new MpvPlayerEngine" in text2:
        ERRORS.append(
            "PlayerManager still contains `new MpvPlayerEngine` after patch — "
            "another construction site needs handling"
        )
    else:
        OK.append("PlayerManager: no remaining new MpvPlayerEngine")


# ---------------------------------------------------------------------------
# Settings UI: setMpvRows always hidden
# ---------------------------------------------------------------------------

def patch_set_mpv_rows(path: Path) -> None:
    text = require_file(path)
    if text is None:
        return

    text = replace_once(
        f"setMpvRows({rel(path)})",
        path,
        text,
        "        boolean visible = PlayerSetting.getPlayer() == PlayerSetting.MPV;",
        "        boolean visible = false; // MPV_DISABLED",
        already_markers=["boolean visible = false; // MPV_DISABLED"],
    )
    save(path, text)


def patch_settings_ui() -> None:
    candidates = [
        ROOT / "app/src/mobile/java/com/fongmi/android/tv/ui/fragment/SettingPlayerFragment.java",
        ROOT / "app/src/leanback/java/com/fongmi/android/tv/ui/fragment/SettingPlayerFragment.java",
        ROOT / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/SettingPlayerActivity.java",
        ROOT / "app/src/mobile/java/com/fongmi/android/tv/ui/activity/SettingPlayerActivity.java",
    ]
    found = 0
    for p in candidates:
        if p.is_file():
            found += 1
            patch_set_mpv_rows(p)
    if found == 0:
        ERRORS.append(
            "No SettingPlayer Fragment/Activity found under app/src/{mobile,leanback}/..."
        )
    else:
        OK.append(f"settings UI: processed {found} file(s)")


# ---------------------------------------------------------------------------
# Final verification (must pass or CI fails)
# ---------------------------------------------------------------------------

def verify() -> None:
    checks = [
        (
            ROOT / "app/src/main/java/com/fongmi/android/tv/setting/PlayerSetting.java",
            [
                "KERNEL_ORDER = {EXO, IJK, SYSTEM}",
                "MPV_DISABLED: MPV is no longer a selectable kernel",
                "MPV_DISABLED: migrate legacy preference",
                "MPV_DISABLED: never persist MPV",
                "MPV_DISABLED: never activate MPV",
            ],
            [],  # MPV constant still referenced for migration guards
        ),
        (
            ROOT / "app/src/main/java/com/fongmi/android/tv/player/PlayerManager.java",
            [
                "MPV_DISABLED: any request for MPV becomes EXO",
                "case PlayerSetting.MPV -> new ExoPlayerEngine",
            ],
            ["new MpvPlayerEngine"],
        ),
    ]
    for path, must_have, must_not in checks:
        if not path.is_file():
            ERRORS.append(f"verify: missing {rel(path)}")
            continue
        text = path.read_text(encoding="utf-8")
        for s in must_have:
            if s not in text:
                ERRORS.append(f"verify FAIL: {rel(path)} missing marker: {s!r}")
            else:
                OK.append(f"verify OK: {rel(path)} has {s!r}")
        for s in must_not:
            if s in text:
                ERRORS.append(f"verify FAIL: {rel(path)} still contains {s!r}")
            else:
                OK.append(f"verify OK: {rel(path)} clean of {s!r}")

    # UI: at least one setMpvRows forced false
    ui_ok = False
    for p in ROOT.glob("app/src/**/SettingPlayer*.java"):
        t = p.read_text(encoding="utf-8")
        if "boolean visible = false; // MPV_DISABLED" in t:
            ui_ok = True
            OK.append(f"verify OK: {rel(p)} setMpvRows forced false")
    if not ui_ok:
        ERRORS.append("verify FAIL: no SettingPlayer* with setMpvRows forced false")


def main() -> int:
    print(f"[mod] inject_disable_mpv ROOT={ROOT}")
    if not (ROOT / "app").is_dir():
        print(f"[mod] ERROR: {ROOT}/app not found — pass repository root", file=sys.stderr)
        return 2

    patch_player_setting()
    patch_player_manager()
    patch_settings_ui()
    verify()

    print("---- results ----")
    for line in OK:
        print("  OK   ", line)
    for line in SKIP:
        print("  SKIP ", line)
    for line in ERRORS:
        print("  FAIL ", line, file=sys.stderr)

    if ERRORS:
        print(f"[mod] FAILED with {len(ERRORS)} error(s)", file=sys.stderr)
        return 1
    print("[mod] inject_disable_mpv SUCCESS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
