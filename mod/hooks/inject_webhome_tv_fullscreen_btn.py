#!/usr/bin/env python3
"""TV WebHome: toolbar fullscreen button + personal setting (default ON)."""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
MARKER = "WEBHOME_TV_FS_BTN"


def patch_setting() -> None:
    path = ROOT / "app/src/main/java/com/fongmi/android/tv/setting/Setting.java"
    if not path.is_file():
        print("[mod] WARN Setting.java missing")
        return
    t = path.read_text(encoding="utf-8")
    if "isWebHomeFullscreenBtn" in t:
        print("[mod] Setting already")
        return
    # insert after putWebHomeFullscreen
    anchor = "public static void putWebHomeFullscreen(boolean fullscreen) {"
    i = t.find(anchor)
    if i < 0:
        print("[mod] WARN putWebHomeFullscreen not found")
        return
    j = t.find("}", i)
    j = t.find("\n", j) + 1
    block = """
    /** TV：是否显示 HTML 站点工具栏全屏按钮，默认开 */
    public static boolean isWebHomeFullscreenBtn() {
        return Prefers.getBoolean("web_home_fullscreen_btn", true);
    }

    public static void putWebHomeFullscreenBtn(boolean enabled) {
        Prefers.put("web_home_fullscreen_btn", enabled);
    }
"""
    path.write_text(t[:j] + block + t[j:], encoding="utf-8")
    print("[mod] Setting isWebHomeFullscreenBtn")


def patch_layout() -> None:
    path = ROOT / "app/src/leanback/res/layout/activity_home.xml"
    if not path.is_file():
        print("[mod] WARN activity_home.xml missing")
        return
    t = path.read_text(encoding="utf-8")
    if 'android:id="@+id/webHomeFullscreen"' in t or "web_home_fullscreen_btn" in t:
        print("[mod] layout already")
        return
    # insert before clock
    needle = '''        <com.google.android.material.textview.MaterialTextView
            android:id="@+id/clock"'''
    insert = '''        <androidx.appcompat.widget.AppCompatImageView
            android:id="@+id/webHomeFullscreen"
            android:layout_width="48dp"
            android:layout_height="48dp"
            android:layout_marginEnd="12dp"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:contentDescription="@string/play_fullscreen"
            android:focusable="true"
            android:focusableInTouchMode="true"
            android:padding="8dp"
            android:src="@drawable/ic_control_fullscreen"
            android:visibility="gone"
            android:nextFocusLeft="@id/title"
            android:nextFocusRight="@id/clock" />

''' + needle
    if needle not in t:
        print("[mod] WARN clock view not found in activity_home")
        return
    path.write_text(t.replace(needle, insert, 1), encoding="utf-8")
    print("[mod] activity_home webHomeFullscreen")


def patch_home_activity() -> None:
    path = ROOT / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/HomeActivity.java"
    if not path.is_file():
        print("[mod] WARN HomeActivity missing")
        return
    t = path.read_text(encoding="utf-8")
    if MARKER in t:
        print("[mod] HomeActivity already")
        return

    # 1) after setWebView / initView ensure listener - hook into showWebOverlay and applyTvChrome
    if "updateWebHomeFullscreenBtn" not in t:
        method = f"""
    // {MARKER}
    private void updateWebHomeFullscreenBtn() {{
        try {{
            android.view.View btn = mBinding.getRoot().findViewById(com.fongmi.android.tv.R.id.webHomeFullscreen);
            if (btn == null) return;
            boolean show = Setting.isWebHomeFullscreenBtn()
                    && Setting.isWebHomeFullscreen()
                    && mWeb != null && mWeb.isVisible()
                    && !TV_FULL.equals(webChromeMode)
                    && !TV_TOOLBAR_HIDDEN.equals(webChromeMode);
            btn.setVisibility(show ? android.view.View.VISIBLE : android.view.View.GONE);
            if (show && !btn.hasOnClickListeners()) {{
                btn.setOnClickListener(v -> {{
                    if (!Setting.isWebHomeFullscreen()) return;
                    applyTvChrome(TV_FULL);
                    updateWebHomeFullscreenBtn();
                }});
                btn.setOnKeyListener((v, keyCode, event) -> {{
                    if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
                    if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER) {{
                        v.performClick();
                        return true;
                    }}
                    return false;
                }});
            }}
        }} catch (Throwable ignored) {{
        }}
    }}
"""
        # insert before applyTvChrome private method
        if "private void applyTvChrome(String mode)" in t:
            t = t.replace(
                "private void applyTvChrome(String mode)",
                method + "\n    private void applyTvChrome(String mode)",
                1,
            )
            print("[mod] HomeActivity updateWebHomeFullscreenBtn method")
        else:
            print("[mod] WARN applyTvChrome not found")

    # call update at end of applyTvChrome
    old_apply = """    private void applyTvChrome(String mode) {
        webChromeMode = mode;
        webToolbarVisible = TV_NORMAL.equals(mode) || TV_OVERLAY.equals(mode);
        updateToolbarVisibility(webToolbarVisible);
        syncWebOverlayLayout();
        if (mWeb != null) mWeb.setViewport(tvViewport(mode));
    }"""
    new_apply = """    private void applyTvChrome(String mode) {
        webChromeMode = mode;
        webToolbarVisible = TV_NORMAL.equals(mode) || TV_OVERLAY.equals(mode);
        updateToolbarVisibility(webToolbarVisible);
        syncWebOverlayLayout();
        if (mWeb != null) mWeb.setViewport(tvViewport(mode));
        updateWebHomeFullscreenBtn();
    }"""
    if old_apply in t:
        t = t.replace(old_apply, new_apply, 1)
        print("[mod] applyTvChrome hook")
    elif "updateWebHomeFullscreenBtn();" not in t.split("applyTvChrome")[1][:400] if "applyTvChrome" in t else True:
        t2, n = re.subn(
            r"(private void applyTvChrome\(String mode\) \{[\s\S]*?if \(mWeb != null\) mWeb\.setViewport\(tvViewport\(mode\)\);)",
            r"\1\n        updateWebHomeFullscreenBtn();",
            t,
            count=1,
        )
        if n:
            t = t2
            print("[mod] applyTvChrome soft hook")
        else:
            print("[mod] WARN applyTvChrome body not hooked")

    # onWebReady
    if "public void onWebReady()" in t and "updateWebHomeFullscreenBtn" in t:
        t2, n = re.subn(
            r"(public void onWebReady\(\) \{)",
            r"\1\n        updateWebHomeFullscreenBtn();",
            t,
            count=1,
        )
        if n:
            t = t2
            print("[mod] onWebReady hook")

    # onWebError already applyTvChrome which hooks

    # initView: wire once
    if "setLogo" in t and "webHomeFullscreen" not in t[t.find("initView"):t.find("initView")+800]:
        pass

    path.write_text(t, encoding="utf-8")
    if t.count("{") != t.count("}"):
        print("[mod] FAIL HomeActivity braces", t.count("{"), t.count("}"))
    else:
        print("[mod] HomeActivity braces ok")


def patch_personal_layout() -> None:
    path = ROOT / "app/src/leanback/res/layout/activity_setting_personal.xml"
    if not path.is_file():
        print("[mod] WARN personal layout missing")
        return
    t = path.read_text(encoding="utf-8")
    if 'android:id="@+id/webHomeFullscreenBtn"' in t:
        print("[mod] personal layout already")
        return
    # append before last closing tags of scroll content - find last LinearLayoutCompat block end near bottom
    row = '''
        <androidx.appcompat.widget.LinearLayoutCompat
            android:id="@+id/webHomeFullscreenBtn"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:background="@drawable/selector_item"
            android:focusable="true"
            android:focusableInTouchMode="true"
            android:orientation="horizontal"
            android:padding="8dp">

            <com.google.android.material.textview.MaterialTextView
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="html站点全屏按钮"
                android:textColor="@color/white"
                android:textSize="18sp" />

            <com.google.android.material.textview.MaterialTextView
                android:id="@+id/webHomeFullscreenBtnText"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:gravity="end"
                android:textColor="@color/white"
                android:textSize="16sp" />
        </androidx.appcompat.widget.LinearLayoutCompat>
'''
    # insert before final closing of vertical parent - look for homeButtonsOrder or last item
    for key in ("homeButtonsOrder", "playControlTransparent", "kaitiScale", "subtitleFont"):
        token = f'android:id="@+id/{key}"'
        if token in t:
            # find end of that LinearLayoutCompat block
            i = t.find(token)
            # find matching close after this block - simplistic: next "        </androidx.appcompat.widget.LinearLayoutCompat>" after i+200
            close = t.find("</androidx.appcompat.widget.LinearLayoutCompat>", i)
            if close > 0:
                close = t.find("\n", close) + 1
                path.write_text(t[:close] + row + t[close:], encoding="utf-8")
                print(f"[mod] personal layout after {key}")
                return
    # fallback: before last NestedScrollView close
    idx = t.rfind("</androidx.appcompat.widget.LinearLayoutCompat>")
    if idx > 0:
        # insert before the outer closing - find second to last vertical container end is hard
        # append before last two closes
        path.write_text(t[:idx] + row + t[idx:], encoding="utf-8")
        print("[mod] personal layout fallback insert")
    else:
        print("[mod] WARN personal layout insert failed")


def patch_personal_java() -> None:
    path = ROOT / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/SettingPersonalActivity.java"
    if not path.is_file():
        print("[mod] WARN SettingPersonalActivity missing")
        return
    t = path.read_text(encoding="utf-8")
    if "setWebHomeFullscreenBtn" in t or MARKER in t:
        print("[mod] personal java already")
        return

    # init listener
    if "mBinding.homeSiteLock.setOnClickListener" in t:
        t = t.replace(
            "mBinding.homeSiteLock.setOnClickListener(this::setHomeSiteLock);",
            "mBinding.homeSiteLock.setOnClickListener(this::setHomeSiteLock);\n"
            "        try { mBinding.webHomeFullscreenBtn.setOnClickListener(this::setWebHomeFullscreenBtn); } catch (Throwable ignored) {}",
            1,
        )
    elif "try { mBinding.searchPanFirst" in t:
        t = t.replace(
            "try { mBinding.searchPanFirst",
            "try { mBinding.webHomeFullscreenBtn.setOnClickListener(this::setWebHomeFullscreenBtn); } catch (Throwable ignored) {}\n"
            "        try { mBinding.searchPanFirst",
            1,
        )
    else:
        print("[mod] WARN personal listener anchor")

    # init text
    init_line = None
    for cand in (
        "mBinding.homeSiteLockText.setText",
        "mBinding.homeHistoryText.setText",
        "mBinding.globalHistoryText.setText",
    ):
        if cand in t:
            init_line = cand
            break
    if init_line:
        t = t.replace(
            init_line,
            "try { mBinding.webHomeFullscreenBtnText.setText(getSwitch(Setting.isWebHomeFullscreenBtn())); } catch (Throwable ignored) {}\n"
            "        " + init_line,
            1,
        )

    # method before last closing brace of class
    method = f"""
    // {MARKER}
    private void setWebHomeFullscreenBtn(View view) {{
        Setting.putWebHomeFullscreenBtn(!Setting.isWebHomeFullscreenBtn());
        try {{
            mBinding.webHomeFullscreenBtnText.setText(getSwitch(Setting.isWebHomeFullscreenBtn()));
        }} catch (Throwable ignored) {{
        }}
    }}
"""
    # insert before final }
    last = t.rfind("}")
    if last > 0:
        t = t[:last] + method + t[last:]
        print("[mod] personal setWebHomeFullscreenBtn")
    path.write_text(t, encoding="utf-8")
    if t.count("{") != t.count("}"):
        print("[mod] FAIL personal braces", t.count("{"), t.count("}"))
    else:
        print("[mod] personal braces ok")


def ensure_drawable() -> None:
    # ic_control_fullscreen may be mobile-only; copy path check
    for sub in (
        "app/src/main/res/drawable/ic_control_fullscreen.xml",
        "app/src/mobile/res/drawable/ic_control_fullscreen.xml",
        "app/src/leanback/res/drawable/ic_control_fullscreen.xml",
    ):
        p = ROOT / sub
        if p.is_file():
            print("[mod] fullscreen icon at", sub)
            # ensure leanback can see it - if only mobile, copy to main
            if "mobile" in sub:
                dst = ROOT / "app/src/main/res/drawable/ic_control_fullscreen.xml"
                if not dst.is_file():
                    dst.parent.mkdir(parents=True, exist_ok=True)
                    dst.write_text(p.read_text(encoding="utf-8"), encoding="utf-8")
                    print("[mod] copied icon to main")
            return
    print("[mod] WARN ic_control_fullscreen not in tree (upstream mobile asset)")


def main() -> None:
    print("[mod] inject_webhome_tv_fullscreen_btn ROOT", ROOT)
    patch_setting()
    patch_layout()
    patch_home_activity()
    patch_personal_layout()
    patch_personal_java()
    ensure_drawable()
    print("[mod] inject_webhome_tv_fullscreen_btn done")


if __name__ == "__main__":
    main()
