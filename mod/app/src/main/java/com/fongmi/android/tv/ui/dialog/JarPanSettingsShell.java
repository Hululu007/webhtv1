package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Vod;
import com.github.catvod.utils.Prefers;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * SHELL_JAR_SETTINGS_REDO v6
 * 淡蓝背景 + 完整线路列表可滚动 + 下载线程需确认 + 账号登录壳子列表带高亮
 */
public final class JarPanSettingsShell {

    public static final String ID_LINE_ORDER = "playLineOrderSettings";
    public static final String ID_THREAD = "panThreadHubSettings";
    public static final String ID_LOGIN = "panLoginHubSettings";

    private static final String PREF_ORDER = "playLineOrder";
    private static final String PREF_THREAD = "shell_jar_download_threads";
    private static final int[] THREAD_OPTS = {1, 2, 3, 4, 6, 8, 12, 16};

    /** 用户偏好的淡蓝背景 */
    private static final int BG_PANEL = 0xFFE3F2FD;
    private static final int BG_ROW = 0xFFFFFFFF;
    private static final int BG_ROW_FOCUS = 0xFFBBDEFB;
    private static final int STROKE_FOCUS = 0xFF1976D2;
    private static final int TEXT_MAIN = 0xFF212121;
    private static final int TEXT_SUB = 0xFF616161;

    private JarPanSettingsShell() {}

    public static boolean isShellPanel(String id) {
        if (TextUtils.isEmpty(id)) return false;
        String s = id.toLowerCase();
        return s.contains("playlineorder")
                || s.contains("panthread")
                || s.contains("panlogin")
                || id.contains(ID_LINE_ORDER)
                || id.contains(ID_THREAD)
                || id.contains(ID_LOGIN);
    }

    public static void open(Activity activity, String siteKey, String id) {
        if (activity == null || activity.isFinishing()) return;
        String s = id == null ? "" : id.toLowerCase();
        if (s.contains("thread") || (id != null && id.contains(ID_THREAD))) {
            openThread(activity);
        } else if (s.contains("login") || (id != null && id.contains(ID_LOGIN))) {
            openLogin(activity, siteKey);
        } else {
            openLineOrder(activity);
        }
    }

    public static void openPost(String siteKey, String id) {
        new Handler(Looper.getMainLooper()).post(() -> {
            Activity a = null;
            try {
                a = com.fongmi.android.tv.App.activity();
            } catch (Throwable ignored) {
            }
            if (a == null || a.isFinishing()) {
                try {
                    a = com.github.catvod.Init.activityForDialog();
                } catch (Throwable ignored) {
                }
            }
            if (a != null && !a.isFinishing() && isShellPanel(id)) open(a, siteKey, id);
        });
    }

    // -------------------- 线路排序 --------------------

    private static void openLineOrder(Activity activity) {
        final List<String> items = mergeOrderList(readOrderPref());
        final int[] sel = {0};

        LinearLayout root = column(activity);
        root.setBackgroundColor(BG_PANEL);
        root.addView(title(activity, "线路排序"));
        root.addView(sub(activity, "上下键移动焦点 · 左右键上/下移 · 保存"));

        final ScrollView scroll = new ScrollView(activity);
        scroll.setFocusable(false);
        scroll.setFillViewport(false);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        // 给足高度，条目多时可滚
        int listH = Math.min(dp(activity, 420), (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.55f));
        final LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        scroll.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, listH));

        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        TextView btnUp = barBtn(activity, "上移");
        TextView btnDown = barBtn(activity, "下移");
        TextView btnSave = barBtn(activity, "保存");
        TextView btnReset = barBtn(activity, "重置");
        bar.addView(btnUp);
        bar.addView(btnDown);
        bar.addView(btnReset);
        bar.addView(btnSave);
        root.addView(bar);

        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            list.removeAllViews();
            for (int i = 0; i < items.size(); i++) {
                final int idx = i;
                LinearLayout row = makeRow(activity, (i + 1) + ".  " + items.get(i), null);
                row.setId(View.generateViewId());
                row.setOnFocusChangeListener((v, has) -> {
                    if (has) {
                        sel[0] = idx;
                        // 焦点行滚入可视区
                        scroll.post(() -> {
                            int top = v.getTop();
                            int bottom = v.getBottom();
                            int sl = scroll.getScrollY();
                            int sh = scroll.getHeight();
                            if (top < sl) scroll.smoothScrollTo(0, top);
                            else if (bottom > sl + sh) scroll.smoothScrollTo(0, bottom - sh);
                        });
                    }
                    applyRowFocus(activity, v, has);
                });
                row.setOnKeyListener((v, keyCode, e) -> {
                    if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                    boolean moved = false;
                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                            || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                            || keyCode == KeyEvent.KEYCODE_ENTER) {
                        if (sel[0] > 0) {
                            String t = items.remove(sel[0]);
                            items.add(sel[0] - 1, t);
                            sel[0]--;
                            moved = true;
                        }
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        if (sel[0] < items.size() - 1) {
                            String t = items.remove(sel[0]);
                            items.add(sel[0] + 1, t);
                            sel[0]++;
                            moved = true;
                        }
                    }
                    if (moved) {
                        int keep = sel[0];
                        refresh[0].run();
                        list.post(() -> {
                            if (keep >= 0 && keep < list.getChildCount())
                                list.getChildAt(keep).requestFocus();
                        });
                        return true;
                    }
                    return false;
                });
                list.addView(row);
            }
            int n = list.getChildCount();
            for (int i = 0; i < n; i++) {
                View c = list.getChildAt(i);
                if (i + 1 < n) c.setNextFocusDownId(list.getChildAt(i + 1).getId());
                else c.setNextFocusDownId(btnUp.getId());
                if (i > 0) c.setNextFocusUpId(list.getChildAt(i - 1).getId());
            }
            if (n > 0) {
                int last = list.getChildAt(n - 1).getId();
                btnUp.setNextFocusUpId(last);
                btnDown.setNextFocusUpId(last);
                btnSave.setNextFocusUpId(last);
                btnReset.setNextFocusUpId(last);
            }
        };

        AlertDialog dialog = buildDialog(activity, root);

        btnUp.setOnClickListener(v -> move(items, sel, -1, refresh, list));
        btnDown.setOnClickListener(v -> move(items, sel, 1, refresh, list));
        btnReset.setOnClickListener(v -> {
            items.clear();
            items.addAll(fullDefaultOrder());
            sel[0] = 0;
            refresh[0].run();
            list.post(() -> {
                if (list.getChildCount() > 0) list.getChildAt(0).requestFocus();
            });
        });
        btnSave.setOnClickListener(v -> {
            saveOrder(items);
            dialog.dismiss();
        });

        dialog.setOnShowListener(d -> {
            styleWindow(dialog);
            refresh[0].run();
            list.post(() -> {
                if (list.getChildCount() > 0) list.getChildAt(0).requestFocus();
            });
        });
        attachDismissFinish(activity, dialog);
        dialog.show();
        sizeWindow(dialog, activity, 0.72f);
    }

    private static void move(List<String> items, int[] sel, int delta, Runnable[] refresh, LinearLayout list) {
        int to = sel[0] + delta;
        if (to < 0 || to >= items.size()) return;
        String t = items.remove(sel[0]);
        items.add(to, t);
        sel[0] = to;
        int keep = sel[0];
        refresh[0].run();
        list.post(() -> {
            if (keep < list.getChildCount()) list.getChildAt(keep).requestFocus();
        });
    }

    /** 已有顺序在前，缺的用完整默认补全，避免迅雷原画等被漏掉 */
    private static List<String> mergeOrderList(List<String> saved) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (saved != null) set.addAll(saved);
        for (String d : fullDefaultOrder()) set.add(d);
        return new ArrayList<>(set);
    }

    private static List<String> readOrderPref() {
        List<String> out = new ArrayList<>();
        try {
            String raw = Prefers.getString(PREF_ORDER, "");
            if (TextUtils.isEmpty(raw)) return out;
            for (String p : raw.split(",")) {
                String s = p.trim();
                if (!s.isEmpty()) out.add(s);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void saveOrder(List<String> items) {
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(items.get(i));
            }
            Prefers.put(PREF_ORDER, sb.toString());
        } catch (Throwable ignored) {
        }
    }

    /** 对齐 jar 常见完整画质线路（含迅雷/移动等） */
    private static List<String> fullDefaultOrder() {
        List<String> d = new ArrayList<>();
        d.add("夸克无限");
        d.add("夸克原画");
        d.add("夸克普画");
        d.add("UC无限");
        d.add("UC原画");
        d.add("百度无限");
        d.add("百度原画");
        d.add("迅雷原画");
        d.add("迅雷会员");
        d.add("阿里原画");
        d.add("天翼原画");
        d.add("115原画");
        d.add("移动原画");
        d.add("光鸭原画");
        return d;
    }

    // -------------------- 下载线程（点选不关，确定才关） --------------------


    private static void openThread(Activity activity) {
        LinearLayout root = column(activity);
        root.setBackgroundColor(BG_PANEL);
        root.addView(title(activity, "下载线程"));
        root.addView(sub(activity, "上下键选择 · OK 确认保存 · 返回取消"));

        int current = nearestThread(Prefers.getInt(PREF_THREAD, 4));
        // also try jar keys
        try {
            String qt = Prefers.getString("quarkThread", "");
            if (!TextUtils.isEmpty(qt)) {
                try { current = nearestThread(Integer.parseInt(qt.trim())); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        final int[] selected = {current};

        final ScrollView scroll = new ScrollView(activity);
        scroll.setFocusable(false);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        LinearLayout opts = new LinearLayout(activity);
        opts.setOrientation(LinearLayout.VERTICAL);
        opts.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        final List<LinearLayout> rows = new ArrayList<>();

        AlertDialog[] dialogRef = new AlertDialog[1];

        Runnable paint = () -> {
            for (LinearLayout r : rows) {
                int n = (Integer) r.getTag();
                boolean on = n == selected[0];
                boolean focused = r.isFocused();
                applyRowFocus(activity, r, focused);
                if (r.getChildCount() >= 1 && r.getChildAt(0) instanceof TextView) {
                    TextView tv = (TextView) r.getChildAt(0);
                    tv.setText((on ? "✓  " : "    ") + n + " 线程");
                    tv.setTypeface(null, on || focused ? Typeface.BOLD : Typeface.NORMAL);
                }
            }
        };

        for (int n : THREAD_OPTS) {
            LinearLayout row = makeRow(activity, (n == current ? "✓  " : "    ") + n + " 线程", null);
            row.setTag(n);
            row.setId(View.generateViewId());
            row.setMinimumHeight(dp(activity, 52));
            row.setOnFocusChangeListener((v, has) -> {
                if (has) selected[0] = (Integer) v.getTag();
                paint.run();
                if (has) {
                    scroll.post(() -> {
                        int top = v.getTop();
                        int bottom = v.getBottom();
                        int sl = scroll.getScrollY();
                        int sh = scroll.getHeight();
                        if (top < sl) scroll.smoothScrollTo(0, top);
                        else if (bottom > sl + sh) scroll.smoothScrollTo(0, bottom - sh);
                    });
                }
            });
            // OK / 点击：选中并保存关闭（符合「OK确认」）
            row.setOnClickListener(v -> {
                selected[0] = (Integer) v.getTag();
                saveThread(selected[0]);
                if (dialogRef[0] != null) dialogRef[0].dismiss();
            });
            row.setOnKeyListener((v, keyCode, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    selected[0] = (Integer) v.getTag();
                    saveThread(selected[0]);
                    if (dialogRef[0] != null) dialogRef[0].dismiss();
                    return true;
                }
                return false;
            });
            rows.add(row);
            opts.addView(row);
        }
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) rows.get(i).setNextFocusUpId(rows.get(i - 1).getId());
            if (i + 1 < rows.size()) rows.get(i).setNextFocusDownId(rows.get(i + 1).getId());
        }
        scroll.addView(opts, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int listH = Math.min(dp(activity, 400),
                (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.5f));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, listH));

        AlertDialog dialog = buildDialog(activity, root);
        dialogRef[0] = dialog;
        dialog.setOnShowListener(d -> {
            styleWindow(dialog);
            for (LinearLayout row : rows) {
                if ((Integer) row.getTag() == selected[0]) {
                    row.post(() -> {
                        row.requestFocus();
                        paint.run();
                    });
                    break;
                }
            }
        });
        attachDismissFinish(activity, dialog);
        dialog.show();
        sizeWindow(dialog, activity, 0.5f);
    }

    private static void saveThread(int n) {
        try {
            Prefers.put(PREF_THREAD, n);
            Prefers.put("quarkThread", String.valueOf(n));
            Prefers.put("ucThread", String.valueOf(n));
            Prefers.put("baiduThread", String.valueOf(n));
        } catch (Throwable ignored) {
        }
    }

    private static int nearestThread(int v) {
        int best = THREAD_OPTS[0], dist = Math.abs(v - best);
        for (int n : THREAD_OPTS) {
            int d = Math.abs(v - n);
            if (d < dist) {
                dist = d;
                best = n;
            }
        }
        return best;
    }

    // -------------------- 账号登录（壳子列表 + 高亮） --------------------

    private static void openLogin(Activity activity, String siteKey) {
        LinearLayout root = column(activity);
        root.setBackgroundColor(BG_PANEL);
        root.addView(title(activity, "选择账号或导入项目"));
        root.addView(sub(activity, "上下键移动焦点 · OK 选择"));

        ScrollView scroll = new ScrollView(activity);
        scroll.setFocusable(false);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        scroll.addView(list);
        int listH = Math.min(dp(activity, 400), (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.5f));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, listH));

        AlertDialog dialog = buildDialog(activity, root);

        Runnable fill = () -> {
            List<String[]> rows = fetchLoginRows(siteKey);
            if (rows.isEmpty()) {
                rows.add(new String[]{"夸克网盘", "点击进入登录/切换"});
                rows.add(new String[]{"UC网盘", "点击进入登录/切换"});
                rows.add(new String[]{"百度网盘", "点击进入登录/切换"});
                rows.add(new String[]{"迅雷网盘", "点击进入登录/切换"});
                rows.add(new String[]{"阿里云盘", "点击进入登录/切换"});
                rows.add(new String[]{"天翼云盘", "点击进入登录/切换"});
                rows.add(new String[]{"115网盘", "点击进入登录/切换"});
                rows.add(new String[]{"移动云盘", "点击进入登录/切换"});
                rows.add(new String[]{"光鸭云盘", "点击进入登录/切换"});
            }
            list.removeAllViews();
            List<LinearLayout> views = new ArrayList<>();
            for (String[] r : rows) {
                LinearLayout row = makeRow(activity, r[0], r.length > 1 ? r[1] : null);
                row.setId(View.generateViewId());
                String name = r[0];
                row.setOnFocusChangeListener((v, has) -> {
                    applyRowFocus(activity, v, has);
                    if (has) {
                        scroll.post(() -> {
                            int top = v.getTop();
                            int bottom = v.getBottom();
                            int sl = scroll.getScrollY();
                            int sh = scroll.getHeight();
                            if (top < sl) scroll.smoothScrollTo(0, top);
                            else if (bottom > sl + sh) scroll.smoothScrollTo(0, bottom - sh);
                        });
                    }
                });
                row.setOnClickListener(v -> {
                    trySelectAccount(siteKey, name);
                    dialog.dismiss();
                });
                views.add(row);
                list.addView(row);
            }
            for (int i = 0; i < views.size(); i++) {
                if (i > 0) views.get(i).setNextFocusUpId(views.get(i - 1).getId());
                if (i + 1 < views.size()) views.get(i).setNextFocusDownId(views.get(i + 1).getId());
            }
            if (!views.isEmpty()) views.get(0).post(views.get(0)::requestFocus);
        };

        dialog.setOnShowListener(d -> {
            styleWindow(dialog);
            Executors.newSingleThreadExecutor().execute(() ->
                    new Handler(Looper.getMainLooper()).post(fill));
        });
        attachDismissFinish(activity, dialog);
        dialog.show();
        sizeWindow(dialog, activity, 0.7f);
    }

    private static List<String[]> fetchLoginRows(String siteKey) {
        List<String[]> out = new ArrayList<>();
        if (TextUtils.isEmpty(siteKey)) return out;
        for (String act : new String[]{"panLoginHubSettings", "getPanAccounts", "listAccounts"}) {
            try {
                Result r = SiteApi.action(siteKey, act);
                if (r == null || r.getList() == null) continue;
                for (Vod v : r.getList()) {
                    if (v == null || TextUtils.isEmpty(v.getName())) continue;
                    out.add(new String[]{v.getName(), v.getRemarks()});
                }
                if (!out.isEmpty()) return out;
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static void trySelectAccount(String siteKey, String name) {
        if (TextUtils.isEmpty(siteKey)) return;
        for (String act : new String[]{"selectAccount:" + name, "panLogin:" + name, name}) {
            try {
                SiteApi.action(siteKey, act);
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    // -------------------- UI helpers --------------------

    private static AlertDialog buildDialog(Activity activity, View root) {
        return new MaterialAlertDialogBuilder(activity)
                .setView(root)
                .setCancelable(true)
                .create();
    }

    private static void styleWindow(AlertDialog dialog) {
        try {
            if (dialog.getWindow() != null) {
                dialog.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(BG_PANEL));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void sizeWindow(AlertDialog dialog, Activity activity, float widthFraction) {
        try {
            int w = (int) (activity.getResources().getDisplayMetrics().widthPixels * widthFraction);
            dialog.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        } catch (Throwable ignored) {
        }
    }

    private static void attachDismissFinish(Activity activity, AlertDialog dialog) {
        dialog.setOnDismissListener(d -> {
            try {
                if (!activity.isFinishing()
                        && activity.getClass().getName().contains("VideoActivity")) {
                    activity.finish();
                }
            } catch (Throwable ignored) {
            }
        });
    }

    private static void applyRowFocus(Activity a, View v, boolean has) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(a, 10));
        g.setColor(has ? BG_ROW_FOCUS : BG_ROW);
        if (has) g.setStroke(dp(a, 3), STROKE_FOCUS);
        else g.setStroke(dp(a, 1), 0xFF90CAF9);
        v.setBackground(g);
    }

    private static LinearLayout makeRow(Activity a, String title, String sub) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setFocusable(true);
        box.setFocusableInTouchMode(true);
        box.setClickable(true);
        applyRowFocus(a, box, false);
        int p = dp(a, 14);
        box.setPadding(p, dp(a, 12), p, dp(a, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(a, 8);
        box.setLayoutParams(lp);
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextColor(TEXT_MAIN);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        t.setTypeface(null, Typeface.BOLD);
        box.addView(t);
        TextView s = new TextView(a);
        s.setText(sub == null ? "" : sub);
        s.setTextColor(TEXT_SUB);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        s.setPadding(0, dp(a, 4), 0, 0);
        box.addView(s);
        return box;
    }

    private static TextView barBtn(Activity a, String text) {
        TextView tv = new TextView(a);
        tv.setText(text);
        tv.setTextColor(TEXT_MAIN);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setGravity(Gravity.CENTER);
        tv.setFocusable(true);
        tv.setFocusableInTouchMode(true);
        tv.setClickable(true);
        int h = dp(a, 44);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, h, 1f);
        lp.setMargins(dp(a, 4), dp(a, 8), dp(a, 4), 0);
        tv.setLayoutParams(lp);
        applyRowFocus(a, tv, false);
        tv.setOnFocusChangeListener((v, has) -> applyRowFocus(a, v, has));
        return tv;
    }

    private static LinearLayout column(Activity a) {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(a, 20);
        root.setPadding(p, p, p, p);
        root.setBackgroundColor(BG_PANEL);
        return root;
    }

    private static TextView title(Activity a, String text) {
        TextView tv = new TextView(a);
        tv.setText(text);
        tv.setTextColor(TEXT_MAIN);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        tv.setTypeface(null, Typeface.BOLD);
        return tv;
    }

    private static TextView sub(Activity a, String text) {
        TextView tv = new TextView(a);
        tv.setText(text);
        tv.setTextColor(TEXT_SUB);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setPadding(0, dp(a, 4), 0, dp(a, 12));
        return tv;
    }

    private static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
