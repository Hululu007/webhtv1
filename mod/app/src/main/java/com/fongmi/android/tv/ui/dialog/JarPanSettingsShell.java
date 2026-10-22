package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.github.catvod.utils.Prefers;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * SHELL_JAR_SETTINGS_REDO v4
 * - 线路排序 / 下载线程：壳子原生 UI（读写 jar 同名 Prefers）
 * - 账号登录：不拦截，仍走 jar（依赖 Init.activityForDialog）
 * - 弹窗关闭前不 finish Activity，避免和 jar 抢生命周期
 */
public final class JarPanSettingsShell {

    public static final String ID_LINE_ORDER = "playLineOrderSettings";
    public static final String ID_THREAD = "panThreadHubSettings";

    private static final String PREF_ORDER = "playLineOrder";
    private static final String PREF_THREAD = "shell_jar_download_threads";
    private static final int[] THREAD_OPTS = {1, 2, 3, 4, 6, 8, 12, 16};

    private static final int FOCUS_STROKE = 0xFF3D9EFF;
    private static final int FOCUS_FILL = 0xFF1A2740;
    private static final int NORMAL_FILL = 0xFF3A3A3C;

    private JarPanSettingsShell() {}

    /** Only line-order / thread — NOT login (login stays jar). */
    public static boolean isShellPanel(String id) {
        if (TextUtils.isEmpty(id)) return false;
        String s = id.toLowerCase();
        if (s.contains("login") || s.contains("panlogin")) return false;
        return s.contains("playlineorder")
                || s.contains("panthread")
                || id.contains(ID_LINE_ORDER)
                || id.contains(ID_THREAD);
    }

    public static void open(Activity activity, String siteKey, String id) {
        if (activity == null || activity.isFinishing()) return;
        String s = id == null ? "" : id.toLowerCase();
        if (s.contains("thread") || (id != null && id.contains(ID_THREAD))) {
            openThread(activity, siteKey);
        } else {
            openLineOrder(activity, siteKey);
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
            if (a != null && !a.isFinishing() && isShellPanel(id)) {
                open(a, siteKey, id);
            }
        });
    }

    // -------------------- 线路排序 --------------------

    private static void openLineOrder(Activity activity, String siteKey) {
        final List<String> items = new ArrayList<>(readOrderPref());
        if (items.isEmpty()) items.addAll(defaultOrder());

        LinearLayout root = column(activity);
        root.addView(title(activity, "线路排序"));
        root.addView(sub(activity, "上下键移动焦点 · 左右键上/下移 · 保存写回 playLineOrder"));

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setFocusable(false);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        final LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        scroll.addView(list, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 320)));

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

        final int[] sel = {0};
        final Runnable[] refresh = new Runnable[1];

        refresh[0] = () -> {
            list.removeAllViews();
            for (int i = 0; i < items.size(); i++) {
                final int idx = i;
                LinearLayout row = makeRow(activity, (i + 1) + ".  " + items.get(i));
                row.setId(View.generateViewId());
                row.setOnFocusChangeListener((v, has) -> {
                    if (has) sel[0] = idx;
                    applyFocusBg(activity, v, has);
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
                            if (keep >= 0 && keep < list.getChildCount()) {
                                list.getChildAt(keep).requestFocus();
                            }
                        });
                        return true;
                    }
                    return false;
                });
                list.addView(row);
            }
            // focus chain list <-> bar
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

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setView(root)
                .setCancelable(true)
                .create();

        btnUp.setOnClickListener(v -> {
            if (sel[0] > 0 && !items.isEmpty()) {
                String t = items.remove(sel[0]);
                items.add(sel[0] - 1, t);
                sel[0]--;
                int keep = sel[0];
                refresh[0].run();
                list.post(() -> {
                    if (keep < list.getChildCount()) list.getChildAt(keep).requestFocus();
                });
            }
        });
        btnDown.setOnClickListener(v -> {
            if (sel[0] < items.size() - 1) {
                String t = items.remove(sel[0]);
                items.add(sel[0] + 1, t);
                sel[0]++;
                int keep = sel[0];
                refresh[0].run();
                list.post(() -> {
                    if (keep < list.getChildCount()) list.getChildAt(keep).requestFocus();
                });
            }
        });
        btnReset.setOnClickListener(v -> {
            items.clear();
            items.addAll(defaultOrder());
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
            try {
                if (dialog.getWindow() != null) {
                    dialog.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
                }
            } catch (Throwable ignored) {
            }
            refresh[0].run();
            list.post(() -> {
                if (list.getChildCount() > 0) list.getChildAt(0).requestFocus();
                else btnSave.requestFocus();
            });
        });
        // finish VideoActivity only after user closes panel (if it is still this activity)
        dialog.setOnDismissListener(d -> {
            try {
                if (!activity.isFinishing()
                        && activity.getClass().getName().contains("VideoActivity")) {
                    activity.finish();
                }
            } catch (Throwable ignored) {
            }
        });
        dialog.show();
        try {
            dialog.getWindow().setLayout(dp(activity, 560), ViewGroup.LayoutParams.WRAP_CONTENT);
        } catch (Throwable ignored) {
        }
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

    private static List<String> defaultOrder() {
        List<String> d = new ArrayList<>();
        d.add("夸克无限");
        d.add("夸克原画");
        d.add("夸克普画");
        d.add("百度无限");
        d.add("百度原画");
        d.add("UC无限");
        d.add("迅雷原画");
        d.add("移动原画");
        return d;
    }

    // -------------------- 下载线程 --------------------

    private static void openThread(Activity activity, String siteKey) {
        LinearLayout root = column(activity);
        root.addView(title(activity, "下载线程"));
        root.addView(sub(activity, "上下键选择 · OK 确认"));

        int current = nearestThread(Prefers.getInt(PREF_THREAD, 4));
        final int[] selected = {current};
        LinearLayout opts = new LinearLayout(activity);
        opts.setOrientation(LinearLayout.VERTICAL);
        opts.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        List<LinearLayout> rows = new ArrayList<>();

        AlertDialog[] dialogRef = new AlertDialog[1];

        for (int n : THREAD_OPTS) {
            LinearLayout row = makeRow(activity, n + " 线程");
            row.setTag(n);
            row.setId(View.generateViewId());
            row.setOnFocusChangeListener((v, has) -> applyFocusBg(activity, v, has));
            row.setOnClickListener(v -> {
                selected[0] = (Integer) v.getTag();
                Prefers.put(PREF_THREAD, selected[0]);
                // also write common jar keys best-effort
                try {
                    Prefers.put("quarkThread", String.valueOf(selected[0]));
                } catch (Throwable ignored) {
                }
                if (dialogRef[0] != null) dialogRef[0].dismiss();
            });
            rows.add(row);
            opts.addView(row);
        }
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) rows.get(i).setNextFocusUpId(rows.get(i - 1).getId());
            if (i + 1 < rows.size()) rows.get(i).setNextFocusDownId(rows.get(i + 1).getId());
        }
        root.addView(opts);

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setView(root)
                .setCancelable(true)
                .create();
        dialogRef[0] = dialog;
        dialog.setOnShowListener(d -> {
            try {
                if (dialog.getWindow() != null)
                    dialog.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            } catch (Throwable ignored) {
            }
            for (LinearLayout row : rows) {
                if ((Integer) row.getTag() == selected[0]) {
                    row.post(row::requestFocus);
                    break;
                }
            }
        });
        dialog.setOnDismissListener(d -> {
            try {
                if (!activity.isFinishing()
                        && activity.getClass().getName().contains("VideoActivity")) {
                    activity.finish();
                }
            } catch (Throwable ignored) {
            }
        });
        dialog.show();
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

    // -------------------- UI --------------------

    private static void applyFocusBg(Activity a, View v, boolean has) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(a, 10));
        g.setColor(has ? FOCUS_FILL : NORMAL_FILL);
        if (has) g.setStroke(dp(a, 2), FOCUS_STROKE);
        v.setBackground(g);
    }

    private static LinearLayout makeRow(Activity a, String title) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setFocusable(true);
        box.setFocusableInTouchMode(true);
        box.setClickable(true);
        applyFocusBg(a, box, false);
        int p = dp(a, 14);
        box.setPadding(p, dp(a, 12), p, dp(a, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(a, 8);
        box.setLayoutParams(lp);
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        box.addView(t);
        return box;
    }

    private static TextView barBtn(Activity a, String text) {
        TextView tv = new TextView(a);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setGravity(Gravity.CENTER);
        tv.setFocusable(true);
        tv.setFocusableInTouchMode(true);
        tv.setClickable(true);
        int h = dp(a, 44);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, h, 1f);
        lp.setMargins(dp(a, 4), dp(a, 8), dp(a, 4), 0);
        tv.setLayoutParams(lp);
        applyFocusBg(a, tv, false);
        tv.setOnFocusChangeListener((v, has) -> applyFocusBg(a, v, has));
        return tv;
    }

    private static LinearLayout column(Activity a) {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(a, 20);
        root.setPadding(p, p, p, p);
        return root;
    }

    private static TextView title(Activity a, String text) {
        TextView tv = new TextView(a);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        tv.setTypeface(null, Typeface.BOLD);
        return tv;
    }

    private static TextView sub(Activity a, String text) {
        TextView tv = new TextView(a);
        tv.setText(text);
        tv.setTextColor(0xFFBBBBBB);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setPadding(0, dp(a, 4), 0, dp(a, 12));
        return tv;
    }

    private static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
