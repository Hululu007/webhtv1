#!/usr/bin/env python3
"""UI polish: home-button focus/√, control transparent, live OSD z-order."""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()


def patch_setting() -> None:
    path = ROOT / "app/src/main/java/com/fongmi/android/tv/setting/Setting.java"
    if not path.is_file():
        print("[mod] WARN Setting.java missing")
        return
    t = path.read_text(encoding="utf-8")
    if "isControlTransparent" in t:
        print("[mod] Setting control transparent already")
        return
    block = """
    public static boolean isControlTransparent() {
        return Prefers.getBoolean("control_transparent", true);
    }

    public static void putControlTransparent(boolean value) {
        Prefers.put("control_transparent", value);
    }
"""
    # insert before last closing brace of class
    last = t.rfind("\n}")
    if last < 0:
        print("[mod] ERROR Setting class end")
        sys.exit(1)
    path.write_text(t[:last] + block + t[last:], encoding="utf-8")
    print("[mod] Setting control transparent helpers")


def patch_spa() -> None:
    layout = ROOT / "app/src/leanback/res/layout/activity_setting_personal.xml"
    java = ROOT / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/SettingPersonalActivity.java"
    if layout.is_file() and 'android:id="@+id/controlTransparent"' not in layout.read_text(encoding="utf-8"):
        t = layout.read_text(encoding="utf-8")
        row = """
        <androidx.appcompat.widget.LinearLayoutCompat
            android:id="@+id/controlTransparent"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:background="@drawable/selector_item"
            android:focusable="true"
            android:orientation="horizontal"
            android:padding="8dp"
            android:visibility="visible">

            <com.google.android.material.textview.MaterialTextView
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="@string/setting_control_transparent"
                android:textColor="@color/white"
                android:textSize="18sp" />

            <com.google.android.material.textview.MaterialTextView
                android:id="@+id/controlTransparentText"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:gravity="end"
                android:textColor="@color/white"
                android:textSize="18sp" />
        </androidx.appcompat.widget.LinearLayoutCompat>
"""
        # after homeButtons if present, else end of playDirectPanPrefer
        if 'android:id="@+id/homeButtons"' in t:
            m = re.search(
                r'android:id="@\+id/homeButtonsText"[\s\S]*?</androidx\.appcompat\.widget\.LinearLayoutCompat>',
                t,
            )
            if m:
                t = t[: m.end()] + "\n" + row + t[m.end() :]
            else:
                print("[mod] WARN homeButtons end not found")
        else:
            m = re.search(
                r'android:id="@\+id/playDirectPanPreferText"[\s\S]*?</androidx\.appcompat\.widget\.LinearLayoutCompat>',
                t,
            )
            if not m:
                print("[mod] ERROR SPA layout anchor")
                sys.exit(1)
            t = t[: m.end()] + "\n" + row + t[m.end() :]
        layout.write_text(t, encoding="utf-8")
        print("[mod] SPA layout controlTransparent")
    if not java.is_file():
        return
    t = java.read_text(encoding="utf-8")
    if "setControlTransparent" in t:
        print("[mod] SPA controlTransparent already")
        return
    if "controlTransparent.setOnClickListener" not in t:
        anchor = "try { mBinding.homeButtons.setOnClickListener(this::onHomeButtons); } catch (Throwable ignored) {}"
        add = (
            anchor
            + "\n        try { mBinding.controlTransparent.setOnClickListener(this::setControlTransparent); } "
            + "catch (Throwable ignored) {}"
        )
        if anchor in t:
            t = t.replace(anchor, add, 1)
        else:
            t = re.sub(
                r"(try \{ mBinding\.\w+\.setOnClickListener\(this::\w+\); \} catch \(Throwable ignored\) \{\}\n)",
                r"\1        try { mBinding.controlTransparent.setOnClickListener(this::setControlTransparent); } "
                r"catch (Throwable ignored) {}\n",
                t,
                count=1,
            )
    if "controlTransparentText.setText" not in t:
        t = re.sub(
            r"(mBinding\.homeButtonsText\.setText\([^\n]+\);\n\s*\} catch \(Throwable ignored\) \{\})",
            r"\1\n        try {\n"
            r"            mBinding.controlTransparentText.setText(getSwitch(Setting.isControlTransparent()));\n"
            r"        } catch (Throwable ignored) {}",
            t,
            count=1,
        )
        if "controlTransparentText.setText" not in t:
            # append near other switches
            t = re.sub(
                r"(try \{ mBinding\.playDirectPanPreferText\.setText\([^\n]+\); \} catch \(Throwable ignored\) \{\})",
                r"\1\n        try { mBinding.controlTransparentText.setText(getSwitch(Setting.isControlTransparent())); } "
                r"catch (Throwable ignored) {}",
                t,
                count=1,
            )
    if "void setControlTransparent" not in t:
        method = """
    private void setControlTransparent(android.view.View view) {
        Setting.putControlTransparent(!Setting.isControlTransparent());
        try {
            mBinding.controlTransparentText.setText(getSwitch(Setting.isControlTransparent()));
        } catch (Throwable ignored) {
        }
    }
"""
        last = t.rfind("\n}")
        t = t[:last] + method + t[last:]
    java.write_text(t, encoding="utf-8")
    print("[mod] SPA controlTransparent logic")


def patch_control_bg() -> None:
    """Apply transparent bg to leanback control roots when setting on — via Video/Live Activity."""
    for rel in [
        "app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
        "app/src/leanback/java/com/fongmi/android/tv/ui/activity/LiveActivity.java",
    ]:
        path = ROOT / rel
        if not path.is_file():
            continue
        t = path.read_text(encoding="utf-8")
        if "applyControlTransparentBg" in t:
            print("[mod] control bg already", rel)
            continue
        helper = """
    private void applyControlTransparentBg() {
        try {
            android.view.View root = mBinding.control.getRoot();
            if (com.fongmi.android.tv.setting.Setting.isControlTransparent()) {
                root.setBackgroundResource(R.drawable.shape_controller_transparent);
            } else {
                root.setBackgroundResource(R.drawable.shape_controller);
            }
        } catch (Throwable ignored) {
        }
    }
"""
        # call after initView / setContent or in onResume
        if "protected void initView()" in t and "applyControlTransparentBg()" not in t:
            t = t.replace(
                "protected void initView() {",
                "protected void initView() {\n        applyControlTransparentBg();",
                1,
            )
        elif "private void initView()" in t and "applyControlTransparentBg()" not in t:
            t = t.replace(
                "private void initView() {",
                "private void initView() {\n        applyControlTransparentBg();",
                1,
            )
        if "applyControlTransparentBg()" not in t and "onResume()" in t:
            t = t.replace(
                "super.onResume();",
                "super.onResume();\n        applyControlTransparentBg();",
                1,
            )
        last = t.rfind("\n}")
        if "applyControlTransparentBg" not in t:
            t = t[:last] + helper + t[last:]
        else:
            # ensure method exists
            if "private void applyControlTransparentBg" not in t:
                t = t[:last] + helper + t[last:]
        path.write_text(t, encoding="utf-8")
        print("[mod] control transparent wire", rel)


def patch_live_osd_z() -> None:
    live = ROOT / "app/src/leanback/res/layout/activity_live.xml"
    if live.is_file():
        t = live.read_text(encoding="utf-8")
        # Force TextureView so OSD can draw above video
        if 'android:id="@+id/exo"' in t and "surface_type" not in t:
            t = t.replace(
                'android:id="@+id/exo"',
                'android:id="@+id/exo"\n                app:surface_type="texture_view"',
                1,
            )
            if "xmlns:app=" not in t:
                t = t.replace(
                    'xmlns:android="http://schemas.android.com/apk/res/android"',
                    'xmlns:android="http://schemas.android.com/apk/res/android"\n    xmlns:app="http://schemas.android.com/apk/res-auto"',
                    1,
                )
            live.write_text(t, encoding="utf-8")
            print("[mod] live PlayerView texture_view")
        # elevation on osd include
        if 'android:id="@+id/osd"' in t and "elevation" not in t.split('android:id="@+id/osd"')[1][:200]:
            t = live.read_text(encoding="utf-8")
            t = t.replace(
                'android:id="@+id/osd"\n            layout="@layout/view_player_osd"',
                'android:id="@+id/osd"\n            layout="@layout/view_player_osd"\n            android:elevation="24dp"',
                1,
            )
            live.write_text(t, encoding="utf-8")
            print("[mod] live osd elevation")

    osd_xml = ROOT / "app/src/main/res/layout/view_player_osd.xml"
    if osd_xml.is_file():
        t = osd_xml.read_text(encoding="utf-8")
        if "android:elevation" not in t:
            t = t.replace(
                'android:importantForAccessibility="no"',
                'android:importantForAccessibility="no"\n    android:elevation="24dp"',
                1,
            )
            osd_xml.write_text(t, encoding="utf-8")
            print("[mod] view_player_osd elevation")

    osd_java = ROOT / "app/src/main/java/com/fongmi/android/tv/ui/custom/PlayerOsdController.java"
    if not osd_java.is_file():
        print("[mod] WARN PlayerOsdController missing")
        return
    t = osd_java.read_text(encoding="utf-8")
    if "bringToFront" in t and "diagnosticsPanel" in t:
        # still ensure
        pass
    needle = "diagnosticsPanel.setVisibility(TextUtils.isEmpty(text.all()) ? View.GONE : View.VISIBLE);"
    if needle in t and "bringToFront()" not in t[t.find(needle) : t.find(needle) + 200]:
        rep = (
            needle
            + "\n        if (diagnosticsPanel.getVisibility() == View.VISIBLE) {\n"
            + "            diagnosticsPanel.bringToFront();\n"
            + "            if (root != null) {\n"
            + "                root.setVisibility(View.VISIBLE);\n"
            + "                root.bringToFront();\n"
            + "                root.setElevation(24f);\n"
            + "            }\n"
            + "        }"
        )
        t = t.replace(needle, rep, 1)
        osd_java.write_text(t, encoding="utf-8")
        print("[mod] PlayerOsdController bringToFront")
    else:
        print("[mod] PlayerOsdController bringToFront skip/already")


def main() -> None:
    print("[mod] inject_ui_polish ROOT", ROOT)
    patch_setting()
    patch_spa()
    patch_control_bg()
    patch_live_osd_z()
    print("[mod] inject_ui_polish done")


if __name__ == "__main__":
    main()
