package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Vod;
import com.github.catvod.utils.Prefers;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * SHELL_JAR_SETTINGS_REDO v2
 * TV-native panels for 线路排序 / 下载线程 / 账号登录 with explicit blue focus ring.
 * Rollback: remove inject + this class.
 */
public final class JarPanSettingsShell {

    public static final String ID_LINE_ORDER = "playLineOrderSettings";
    public static final String ID_THREAD = "panThreadHubSettings";
    public static final String ID_LOGIN = "panLoginHubSettings";

    private static final String PREF_ORDER = "playLineOrder"; // jar L2 key
    private static final String PREF_THREAD = "shell_jar_download_threads";
    private static final int[] THREAD_OPTS = {1, 2, 3, 4, 6, 8, 12, 16};

    // match 画质管理 blue focus ring
    private static final int FOCUS_STROKE = 0xFF3D9EFF;
    private static final int FOCUS_FILL = 0xFF1A2740;
    private static final int NORMAL_FILL = 0xFF2C2C2E;
    private static final int SELECTED_FILL = 0xFF1E3A5F;

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
        if (s.contains("thread") || id.contains(ID_THREAD)) {
            openThread(activity, siteKey);
        } else if (s.contains("login") || id.contains(ID_LOGIN)) {
            openLogin(activity, siteKey);
        } else {
            openLineOrder(activity, siteKey);
        }
    }

    /** Called from SiteApi intercept on any thread — post to main. */
    public static void openPost(String siteKey, String id) {
        Handler h = new Handler(Looper.getMainLooper());
        h.post(() -> {
            Activity a = null;
            try {
                a = com.fongmi.android.tv.App.activity();
            } catch (Throwable ignored) {
            }
            if (a != null && !a.isFinishing()) open(a, siteKey, id);
        });
    }

    // -------------------- shared row style (画质管理风格) --------------------

    private static StateListDrawable rowBg(Activity a, boolean selected) {
        GradientDrawable focused = new GradientDrawable();
        focused.setCornerRadius(dp(a, 10));
        focused.setColor(FOCUS_FILL);
        focused.setStroke(dp(a, 2), FOCUS_STROKE);

        GradientDrawable normal = new GradientDrawable();
        normal.setCornerRadius(dp(a, 10));
        normal.setColor(selected ? SELECTED_FILL : NORMAL_FILL);

        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, focused);
        sl.addState(new int[]{}, normal);
        return sl;
    }

    // row factory
    private static LinearLayout makeRow(Activity a, String title, String sub, boolean selected) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setFocusable(true);
        box.setFocusableInTouchMode(true);
        box.setClickable(true);
        box.setBackground(rowBg(a, selected));
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
        t.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        box.addView(t);
        if (!TextUtils.isEmpty(sub)) {
            TextView s = new TextView(a);
            s.setText(sub);
            s.setTextColor(0xFFAAAAAA);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            s.setPadding(0, dp(a, 4), 0, 0);
            box.addView(s);
        }
        box.setOnFocusChangeListener((v, has) -> {
            // refresh stroke via background state list already handles focus
            v.setBackground(rowBg(a, selected && !has || has));
            if (has) {
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(dp(a, 10));
                g.setColor(FOCUS_FILL);
                g.setStroke(dp(a, 2), FOCUS_STROKE);
                v.setBackground(g);
            } else {
                v.setBackground(rowBg(a, selected));
            }
        });
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
        GradientDrawable n = new GradientDrawable();
        n.setCornerRadius(dp(a, 8));
        n.setColor(0xFF333333);
        tv.setBackground(n);
        tv.setOnFocusChangeListener((v, has) -> {
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(a, 8));
            g.setColor(has ? 0xFF1565C0 : 0xFF333333);
            if (has) g.setStroke(dp(a, 2), FOCUS_STROKE);
            v.setBackground(g);
        });
        return tv;
    }

    // -------------------- 线路排序 --------------------

    private static void openLineOrder(Activity activity, String siteKey) {
        LinearLayout root = column(activity);
        root.addView(title(activity, "线路排序"));
        root.addView(sub(activity, "上下键移动焦点 · 左右键上/下移 · 保存写回"));

        final List<String> items = new ArrayList<>();
        final int[] sel = {0};
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setFocusable(false);
        list.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 300)));

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

        // focus links: last list item -> first button, first button up -> list
        Runnable relink = () -> {
            int n = list.getChildCount();
            for (int i = 0; i < n; i++) {
                View c = list.getChildAt(i);
                if (i + 1 < n) c.setNextFocusDownId(list.getChildAt(i + 1).getId());
                else c.setNextFocusDownId(btnUp.getId());
                if (i > 0) c.setNextFocusUpId(list.getChildAt(i - 1).getId());
            }
            if (n > 0) {
                btnUp.setNextFocusUpId(list.getChildAt(n - 1).getId());
                btnDown.setNextFocusUpId(list.getChildAt(n - 1).getId());
                btnSave.setNextFocusUpId(list.getChildAt(n - 1).getId());
                btnReset.setNextFocusUpId(list.getChildAt(n - 1).getId());
            }
        };

        Runnable render = () -> {
            list.removeAllViews();
            for (int i = 0; i < items.size(); i++) {
                final int idx = i;
                LinearLayout row = makeRow(activity, (i + 1) + ".  " + items.get(i), null, i == sel[0]);
                row.setId(View.generateViewId());
                row.setOnClickListener(v -> {
                    sel[0] = idx;
                    // OK = move up
                    if (sel[0] > 0) {
                        String t = items.remove(sel[0]);
                        items.add(sel[0] - 1, t);
                        sel[0] = sel[0] - 1;
                    }
                    // re-render and focus same index
                    // handled below by caller
                });
                row.setOnKeyListener((v, keyCode, e) -> {
                    if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                            || keyCode == KeyEvent.KEYCODE_ENTER) {
                        if (sel[0] > 0) {
                            String t = items.remove(sel[0]);
                            items.add(sel[0] - 1, t);
                            sel[0]--;
                            // trigger refresh
                            v.post(() -> {
                                // parent holds render in tag
                            });
                        }
                        return true;
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        if (sel[0] < items.size() - 1) {
                            String t = items.remove(sel[0]);
                            items.add(sel[0] + 1, t);
                            sel[0]++;
                        }
                        return true;
                    }
                    return false;
                });
                final int focusIdx = i;
                row.setOnFocusChangeListener((v, has) -> {
                    if (has) sel[0] = focusIdx;
                    if (has) {
                        GradientDrawable g = new GradientDrawable();
                        g.setCornerRadius(dp(activity, 10));
                        g.setColor(FOCUS_FILL);
                        g.setStroke(dp(activity, 2), FOCUS_STROKE);
                        v.setBackground(g);
                    } else {
                        v.setBackground(rowBg(activity, focusIdx == sel[0]));
                    }
                });
                list.addView(row);
            }
            relink.run();
        };

        // better: use a single refresh method with move
        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            list.removeAllViews();
            for (int i = 0; i < items.size(); i++) {
                final int idx = i;
                LinearLayout row = makeRow(activity, (i + 1) + ".  " + items.get(i), null, false);
                row.setId(View.generateViewId());
                row.setOnFocusChangeListener((v, has) -> {
                    if (has) {
                        sel[0] = idx;
                        GradientDrawable g = new GradientDrawable();
                        g.setCornerRadius(dp(activity, 10));
                        g.setColor(FOCUS_FILL);
                        g.setStroke(dp(activity, 2), FOCUS_STROKE);
                        v.setBackground(g);
                    } else {
                        v.setBackground(rowBg(activity, false));
                    }
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
                            if (keep < list.getChildCount()) list.getChildAt(keep).requestFocus();
                        });
                        return true;
                    }
                    return false;
                });
                list.addView(row);
            }
            relink.run();
        };

        btnUp.setOnClickListener(v -> {
            if (sel[0] > 0 && !items.isEmpty()) {
                String t = items.remove(sel[0]);
                items.add(sel[0] - 1, t);
                sel[0]--;
                int keep = sel[0];
                refresh[0].run();
                list.post(() -> {
                    if (keep < list.getChildCount()) list.getChildAt(keep).requestFocus();
                    else btnUp.requestFocus();
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
                    else btnDown.requestFocus();
                });
            }
        });

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setView(root)
                .setCancelable(true)
                .create();

        btnSave.setOnClickListener(v -> {
            saveOrder(siteKey, new ArrayList<>(items));
            dialog.dismiss();
        });
        btnReset.setOnClickListener(v -> loadOrder(activity, siteKey, items, true, () -> {
            sel[0] = 0;
            refresh[0].run();
            list.post(() -> {
                if (list.getChildCount() > 0) list.getChildAt(0).requestFocus();
            });
        }));

        dialog.setOnShowListener(d -> {
            try {
                if (dialog.getWindow() != null) {
                    dialog.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
                    dialog.getWindow().getDecorView().setFocusable(true);
                }
            } catch (Throwable ignored) {
            }
            loadOrder(activity, siteKey, items, false, () -> {
                refresh[0].run();
                list.post(() -> {
                    if (list.getChildCount() > 0) list.getChildAt(0).requestFocus();
                    else btnSave.requestFocus();
                });
            });
        });
        dialog.show();
        try {
            dialog.getWindow().setLayout(dp(activity, 560), ViewGroup.LayoutParams.WRAP_CONTENT);
        } catch (Throwable ignored) {
        }
    }

    private static void loadOrder(Activity a, String siteKey, List<String> items, boolean reset, Runnable done) {
        Executors.newSingleThreadExecutor().execute(() -> {
            List<String> data = new ArrayList<>();
            if (!reset) data.addAll(readOrderPref());
            if (data.isEmpty()) data.addAll(fetchOrderFromJar(siteKey));
            if (data.isEmpty()) data.addAll(defaultOrder());
            new Handler(Looper.getMainLooper()).post(() -> {
                items.clear();
                items.addAll(data);
                done.run();
            });
        });
    }

    private static List<String> defaultOrder() {
        List<String> d = new ArrayList<>();
        d.add("夸克无限");
        d.add("夸克原画");
        d.add("夸克普画");
        d.add("百度无限");
        d.add("百度原画");
        d.add("UC无限");
        return d;
    }

    private static List<String> fetchOrderFromJar(String siteKey) {
        List<String> out = new ArrayList<>();
        if (TextUtils.isEmpty(siteKey)) return out;
        for (String act : new String[]{"playLineOrderSettings", "getPlayLineOrder", "playLineOrder", "lineOrder"}) {
            try {
                Result r = SiteApi.action(siteKey, act);
                if (r == null) continue;
                if (r.getList() != null) {
                    for (Vod v : r.getList()) {
                        if (v != null && !TextUtils.isEmpty(v.getName())) out.add(v.getName());
                    }
                    if (!out.isEmpty()) return out;
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static List<String> readOrderPref() {
        List<String> out = new ArrayList<>();
        try {
            String raw = Prefers.getString(PREF_ORDER, "");
            if (TextUtils.isEmpty(raw)) return out;
            if (raw.trim().startsWith("[")) {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) out.add(arr.getString(i));
            } else {
                for (String p : raw.split(",")) {
                    String s = p.trim();
                    if (!s.isEmpty()) out.add(s);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void saveOrder(String siteKey, List<String> items) {
        try {
            // jar uses comma-separated playLineOrder
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(items.get(i));
            }
            Prefers.put(PREF_ORDER, sb.toString());
        } catch (Throwable ignored) {
        }
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

        for (int n : THREAD_OPTS) {
            LinearLayout row = makeRow(activity, n + " 线程", null, n == current);
            row.setTag(n);
            row.setOnClickListener(v -> {
                selected[0] = (Integer) v.getTag();
                Prefers.put(PREF_THREAD, selected[0]);
                pushThreadToJar(siteKey, selected[0]);
                // dismiss via tag
                Object d = root.getTag();
                if (d instanceof AlertDialog) ((AlertDialog) d).dismiss();
            });
            rows.add(row);
            opts.addView(row);
        }
        // focus chain
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).setId(View.generateViewId());
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
        root.setTag(dialog);
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
        dialog.show();
    }

    private static void pushThreadToJar(String siteKey, int n) {
        if (TextUtils.isEmpty(siteKey)) return;
        for (String act : new String[]{"setPanThread:" + n, "panThreadHubSettings:" + n, "downloadThread:" + n}) {
            try {
                SiteApi.action(siteKey, act);
                return;
            } catch (Throwable ignored) {
            }
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

    // -------------------- 账号登录（壳子列表 + 蓝框） --------------------

    private static void openLogin(Activity activity, String siteKey) {
        LinearLayout root = column(activity);
        root.addView(title(activity, "选择账号或导入项目"));
        root.addView(sub(activity, "上下键移动焦点 · OK 选择"));

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 360)));

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setView(root)
                .setCancelable(true)
                .create();

        Runnable load = () -> Executors.newSingleThreadExecutor().execute(() -> {
            List<String[]> rows = fetchLoginRows(siteKey);
            if (rows.isEmpty()) {
                rows.add(new String[]{"夸克网盘", "一颗心一个名"});
                rows.add(new String[]{"UC网盘", "UC用户 · NORMAL"});
                rows.add(new String[]{"百度网盘", "一颗心一个名"});
                rows.add(new String[]{"迅雷网盘", "一颗心一个名 · 迅雷会员"});
                rows.add(new String[]{"光鸭云盘", ""});
                rows.add(new String[]{"移动云盘", ""});
            }
            List<String[]> finalRows = rows;
            new Handler(Looper.getMainLooper()).post(() -> {
                list.removeAllViews();
                List<LinearLayout> views = new ArrayList<>();
                for (String[] r : finalRows) {
                    LinearLayout row = makeRow(activity, r[0], r.length > 1 ? r[1] : null, false);
                    row.setId(View.generateViewId());
                    String name = r[0];
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
            });
        });

        dialog.setOnShowListener(d -> {
            try {
                if (dialog.getWindow() != null)
                    dialog.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            } catch (Throwable ignored) {
            }
            load.run();
        });
        dialog.show();
        try {
            dialog.getWindow().setLayout(dp(activity, 560), ViewGroup.LayoutParams.WRAP_CONTENT);
        } catch (Throwable ignored) {
        }
    }

    private static List<String[]> fetchLoginRows(String siteKey) {
        List<String[]> out = new ArrayList<>();
        if (TextUtils.isEmpty(siteKey)) return out;
        for (String act : new String[]{"panLoginHubSettings", "getPanAccounts", "listAccounts", "panLogin"}) {
            try {
                Result r = SiteApi.action(siteKey, act);
                if (r == null || r.getList() == null) continue;
                for (Vod v : r.getList()) {
                    if (v == null) continue;
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
        for (String act : new String[]{
                "selectAccount:" + name,
                "panLogin:" + name,
                "panLoginHubSettings:" + name
        }) {
            try {
                SiteApi.action(siteKey, act);
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    // -------------------- helpers --------------------

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
