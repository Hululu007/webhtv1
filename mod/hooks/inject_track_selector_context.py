#!/usr/bin/env python3
"""Harden DefaultTrackSelector to always use Application Context (Media3 requirement).

Does NOT change track selection parameters / preferred MIME / tunneling / offload.
Only ensures the Context constructor path is non-null Application context when possible.

SpeakerLayoutUtil stereo fallback is device-side; intentionally not modified here.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
EXO = ROOT / "app/src/main/java/com/fongmi/android/tv/player/exo/ExoUtil.java"

OLD = """        DefaultTrackSelector trackSelector = new DefaultTrackSelector(App.get());
        DefaultTrackSelector.Parameters.Builder builder = trackSelector.buildUponParameters();"""

NEW = """        // Media3: channel-count constraints need a non-null Context on the selector.
        // Use Application context; keep parameter policy identical to upstream.
        android.content.Context __tsCtx = App.get();
        if (__tsCtx != null) {
            try { __tsCtx = __tsCtx.getApplicationContext(); } catch (Throwable ignored) {}
        }
        DefaultTrackSelector trackSelector = __tsCtx != null
                ? new DefaultTrackSelector(__tsCtx)
                : new DefaultTrackSelector(App.get());
        DefaultTrackSelector.Parameters.Builder builder = trackSelector.buildUponParameters();"""


def main() -> None:
    if not EXO.exists():
        print("[mod] skip missing ExoUtil")
        return
    t = EXO.read_text(encoding="utf-8")
    if "__tsCtx" in t or "getApplicationContext()" in t and "DefaultTrackSelector(__tsCtx)" in t:
        print("[mod] DefaultTrackSelector Context already hardened")
        return
    if OLD in t:
        t = t.replace(OLD, NEW, 1)
        EXO.write_text(t, encoding="utf-8")
        print("[mod] DefaultTrackSelector -> Application Context")
        return
    # soft: only the constructor line
    t2, n = re.subn(
        r"DefaultTrackSelector trackSelector = new DefaultTrackSelector\(App\.get\(\)\);",
        "android.content.Context __tsCtx = App.get();\n"
        "        if (__tsCtx != null) {\n"
        "            try { __tsCtx = __tsCtx.getApplicationContext(); } catch (Throwable ignored) {}\n"
        "        }\n"
        "        DefaultTrackSelector trackSelector = __tsCtx != null\n"
        "                ? new DefaultTrackSelector(__tsCtx)\n"
        "                : new DefaultTrackSelector(App.get());",
        t,
        count=1,
    )
    if n:
        EXO.write_text(t2, encoding="utf-8")
        print("[mod] DefaultTrackSelector soft Context harden")
    else:
        print("[mod] WARN buildTrackSelector constructor not found (may already match upstream)")


if __name__ == "__main__":
    main()
