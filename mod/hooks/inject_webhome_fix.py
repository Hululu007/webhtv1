#!/usr/bin/env python3
"""WebHome TV focus assist + light mobile hardening."""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
MARKER = "WEBHOME_TV_FOCUS_FIX"


def patch_webview_util() -> None:
    path = ROOT / "app/src/main/java/com/fongmi/android/tv/utils/WebViewUtil.java"
    if not path.is_file():
        print("[mod] WARN WebViewUtil missing")
        return
    t = path.read_text(encoding="utf-8")
    if MARKER in t:
        print("[mod] WebViewUtil already")
        return
    # Append after configureHome body end if setNeedInitialFocus missing
    if "setNeedInitialFocus" not in t:
        t2, n = re.subn(
            r"(public static void configureHome\(WebView webView\) \{)([\s\S]*?)(\n    \})",
            r"\1\2\n        // " + MARKER + r"""
        try { webView.getSettings().setNeedInitialFocus(true); } catch (Throwable ignored) {}
        try { webView.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES); } catch (Throwable ignored) {}
\3""",
            t,
            count=1,
        )
        if n:
            t = t2
            print("[mod] WebViewUtil NeedInitialFocus")
        else:
            print("[mod] WARN configureHome not matched")
    path.write_text(t, encoding="utf-8")


def patch_home_web_controller() -> None:
    path = ROOT / "app/src/main/java/com/fongmi/android/tv/web/HomeWebController.java"
    if not path.is_file():
        print("[mod] WARN HomeWebController missing")
        return
    t = path.read_text(encoding="utf-8")
    if "injectTvSearchFocusAssist" in t:
        print("[mod] HomeWebController already")
        return

    old = """    private boolean focusWebView(String reason) {
        if (webView.hasFocus()) return true;
        boolean ok = webView.requestFocus();
        SpiderDebug.log("webhome-focus", "request reason=%s ok=%s visible=%s width=%s height=%s url=%s", reason, ok, isVisible(), webView.getWidth(), webView.getHeight(), webView.getUrl());
        return ok;
    }"""

    new = """    private boolean focusWebView(String reason) {
        try {
            webView.setFocusable(true);
            webView.setFocusableInTouchMode(true);
        } catch (Throwable ignored) {
        }
        if (webView.hasFocus()) {
            if (Util.isLeanback()) injectTvSearchFocusAssist();
            return true;
        }
        boolean ok = webView.requestFocus();
        if (ok && Util.isLeanback()) injectTvSearchFocusAssist();
        SpiderDebug.log("webhome-focus", "request reason=%s ok=%s visible=%s width=%s height=%s url=%s", reason, ok, isVisible(), webView.getWidth(), webView.getHeight(), webView.getUrl());
        return ok;
    }

    /** """ + MARKER + """：遥控器焦点进入页面搜索框/按钮 */
    private void injectTvSearchFocusAssist() {
        try {
            String js = "(function(){try{"
                + "var d=document;if(!d||!d.documentElement)return;"
                + "d.documentElement.classList.add('fm-tv');"
                + "var q=d.getElementById('q')||d.querySelector('input[type=search],input[type=text]');"
                + "if(q){try{q.setAttribute('tabindex','0');}catch(e){}}"
                + "try{d.querySelectorAll('button,.tab,.gear,.go,.chip').forEach(function(el){el.setAttribute('tabindex','0');});}catch(e){}"
                + "if(!window.__fmTvFocusAssist){window.__fmTvFocusAssist=1;"
                + "d.addEventListener('keydown',function(e){"
                + "var k=e.keyCode||e.which;"
                + "var ae=d.activeElement;var tag=ae&&ae.tagName?ae.tagName.toLowerCase():'';"
                + "if((k===40||k===38||k===37||k===39||k===13)&&(tag==='body'||tag==='html'||!ae||ae===d.body)){"
                + "var t=d.getElementById('q')||d.querySelector('input,button.tab,button');"
                + "if(t&&t.focus){try{t.focus();}catch(ex){}}"
                + "}"
                + "},true);}"
                + "}catch(e){}})();";
            webView.post(() -> webView.evaluateJavascript(js, null));
        } catch (Throwable ignored) {
        }
    }"""

    if old not in t:
        print("[mod] WARN focusWebView block missing")
        path.write_text(t, encoding="utf-8")
        return
    t = t.replace(old, new, 1)
    if 'focusWebView("page-finished")' in t:
        t = t.replace(
            'focusWebView("page-finished");',
            'focusWebView("page-finished");\n                if (Util.isLeanback()) injectTvSearchFocusAssist();',
            1,
        )
    path.write_text(t, encoding="utf-8")
    print("[mod] HomeWebController TV focus assist")


def main() -> None:
    print("[mod] inject_webhome_fix ROOT", ROOT)
    patch_webview_util()
    patch_home_web_controller()
    print("[mod] inject_webhome_fix done")


if __name__ == "__main__":
    main()
