package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.app.Dialog;
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
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Vod;
import com.github.catvod.utils.Prefers;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SHELL_JAR_SETTINGS_REDO — TV-native UI for jar 综合设置 sub-panels.
 * Replaces jar AlertDialog ListView/ScrollView that break remote focus.
 * Data: best-effort spider.action + Prefers fallback.
 * Rollback: remove inject_jar_settings_shell.py and this class.
 */
public final class JarPanSettingsShell {

    public static final String ID_LINE_ORDER = "playLineOrderSettings";
    public static final String ID_THREAD = "panThreadHubSettings";

    private static final String PREF_ORDER = "shell_jar_line_order_json";
    private static final String PREF_THREAD = "shell_jar_download_threads";
    private static final int[] THREAD_OPTS = {1, 2, 3, 4, 6, 8, 12, 16};

    private JarPanSettingsShell() {}

    public static boolean isShellPanel(String id) {
        if (TextUtils.isEmpty(id)) return false;
        return id.contains(ID_LINE_ORDER) || id.contains(ID_THREAD)
                || id.contains("playLineOrder") || id.contains("panThreadHub");
    }

    public static void open(Activity activity, String siteKey, String id) {
        if (activity == null || activity.isFinishing()) return;
        if (id != null && (id.contains(ID_THREAD) || id.contains("panThreadHub"))) {
            openThread(activity, siteKey);
        } else {
            openLineOrder(activity, siteKey);
        }
    }

    // -------------------- 线路排序 --------------------

    private static void openLineOrder(Activity activity, String siteKey) {
        LinearLayout root = column(activity);
        TextView title = label(activity, "线路排序", 22, true);
        TextView hint = label(activity, "上下键选中 · OK 上移 · 菜单键下移 · 返回保存", 14, false);
        hint.setPadding(0, dp(activity, 4), 0, dp(activity, 12));
        root.addView(title);
        root.addView(hint);

        RecyclerView list = new RecyclerView(activity);
        list.setLayoutManager(new LinearLayoutManager(activity));
        list.setHasFixedSize(true);
        list.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        list.setFocusable(true);
        LineAdapter adapter = new LineAdapter();
        list.setAdapter(adapter);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 320));
        root.addView(list, lp);

        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.END);
        TextView btnUp = actionBtn(activity, "上移");
        TextView btnDown = actionBtn(activity, "下移");
        TextView btnSave = actionBtn(activity, "保存");
        TextView btnReset = actionBtn(activity, "重置");
        bar.addView(btnUp);
        bar.addView(btnDown);
        bar.addView(btnReset);
        bar.addView(btnSave);
        root.addView(bar);

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setView(root)
                .setCancelable(true)
                .create();

        btnUp.setOnClickListener(v -> adapter.moveSelected(-1));
        btnDown.setOnClickListener(v -> adapter.moveSelected(1));
        btnReset.setOnClickListener(v -> loadOrderAsync(activity, siteKey, adapter, true));
        btnSave.setOnClickListener(v -> {
            saveOrder(siteKey, adapter.snapshot());
            dialog.dismiss();
        });

        list.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                adapter.moveSelected(-1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_INFO) {
                adapter.moveSelected(1);
                return true;
            }
            return false;
        });

        dialog.setOnShowListener(d -> {
            try {
                dialog.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
                dialog.getWindow().setLocalFocus(true, true);
            } catch (Throwable ignored) {
            }
            loadOrderAsync(activity, siteKey, adapter, false);
            list.post(() -> {
                if (adapter.getItemCount() > 0) {
                    RecyclerView.ViewHolder vh = list.findViewHolderForAdapterPosition(0);
                    if (vh != null) vh.itemView.requestFocus();
                    else list.requestFocus();
                }
            });
        });
        dialog.show();
        try {
            dialog.getWindow().setLayout(dp(activity, 520), ViewGroup.LayoutParams.WRAP_CONTENT);
        } catch (Throwable ignored) {
        }
    }

    private static void loadOrderAsync(Activity activity, String siteKey, LineAdapter adapter, boolean reset) {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        Handler h = new Handler(Looper.getMainLooper());
        ex.execute(() -> {
            List<String> items = new ArrayList<>();
            if (!reset) {
                items.addAll(readOrderPref());
                if (items.isEmpty()) items.addAll(fetchOrderFromJar(siteKey));
            } else {
                items.addAll(fetchOrderFromJar(siteKey));
                if (items.isEmpty()) items.addAll(defaultOrder());
            }
            if (items.isEmpty()) items.addAll(defaultOrder());
            List<String> finalItems = items;
            h.post(() -> adapter.setItems(finalItems));
            ex.shutdown();
        });
    }

    private static List<String> defaultOrder() {
        List<String> d = new ArrayList<>();
        d.add("夸克");
        d.add("UC");
        d.add("百度");
        d.add("迅雷");
        d.add("阿里");
        d.add("天翼");
        d.add("115");
        d.add("移动");
        return d;
    }

    private static List<String> fetchOrderFromJar(String siteKey) {
        List<String> out = new ArrayList<>();
        if (TextUtils.isEmpty(siteKey)) return out;
        String[] acts = {
                "playLineOrderSettings",
                "getPlayLineOrder",
                "playLineOrder",
                "lineOrder",
                "getLineOrder"
        };
        for (String act : acts) {
            try {
                Result r = SiteApi.action(siteKey, act);
                if (r == null) continue;
                // list of vod names
                if (r.getList() != null && !r.getList().isEmpty()) {
                    for (Vod v : r.getList()) {
                        if (v != null && !TextUtils.isEmpty(v.getName())) out.add(v.getName());
                    }
                    if (!out.isEmpty()) return out;
                }
                String msg = r.getMsg();
                if (!TextUtils.isEmpty(msg) && msg.trim().startsWith("[")) {
                    JSONArray arr = new JSONArray(msg);
                    for (int i = 0; i < arr.length(); i++) out.add(arr.getString(i));
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
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) out.add(arr.getString(i));
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void saveOrder(String siteKey, List<String> items) {
        try {
            JSONArray arr = new JSONArray();
            for (String s : items) arr.put(s);
            Prefers.put(PREF_ORDER, arr.toString());
        } catch (Throwable ignored) {
        }
        if (TextUtils.isEmpty(siteKey)) return;
        // best-effort push to jar
        try {
            JSONObject body = new JSONObject();
            body.put("order", new JSONArray(items));
            String[] acts = {
                    "setPlayLineOrder:" + body,
                    "playLineOrderSettings:" + body,
                    "savePlayLineOrder:" + body.toString()
            };
            for (String act : acts) {
                try {
                    SiteApi.action(siteKey, act);
                    break;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // -------------------- 下载线程 --------------------

    private static void openThread(Activity activity, String siteKey) {
        LinearLayout root = column(activity);
        root.addView(label(activity, "下载线程", 22, true));
        root.addView(label(activity, "上下键选择 · OK 确认", 14, false));

        int current = Prefers.getInt(PREF_THREAD, 4);
        current = nearestThread(current);

        LinearLayout opts = new LinearLayout(activity);
        opts.setOrientation(LinearLayout.VERTICAL);
        opts.setPadding(0, dp(activity, 12), 0, 0);
        List<TextView> rows = new ArrayList<>();
        final int[] selected = {current};

        for (int n : THREAD_OPTS) {
            TextView row = actionBtn(activity, n + " 线程");
            row.setTag(n);
            row.setFocusable(true);
            row.setClickable(true);
            if (n == current) {
                row.setTypeface(null, Typeface.BOLD);
                row.setBackgroundColor(0xFF1E3A5F);
            }
            row.setOnClickListener(v -> {
                selected[0] = (Integer) v.getTag();
                for (TextView t : rows) {
                    int tn = (Integer) t.getTag();
                    t.setTypeface(null, tn == selected[0] ? Typeface.BOLD : Typeface.NORMAL);
                    t.setBackgroundColor(tn == selected[0] ? 0xFF1E3A5F : Color.TRANSPARENT);
                }
            });
            rows.add(row);
            opts.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 48)));
        }
        root.addView(opts);

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setView(root)
                .setPositiveButton("确定", (d, w) -> {
                    Prefers.put(PREF_THREAD, selected[0]);
                    pushThreadToJar(siteKey, selected[0]);
                })
                .setNegativeButton("取消", null)
                .create();

        dialog.setOnShowListener(d -> {
            try {
                dialog.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            } catch (Throwable ignored) {
            }
            for (TextView t : rows) {
                if ((Integer) t.getTag() == selected[0]) {
                    t.post(t::requestFocus);
                    break;
                }
            }
        });
        dialog.show();
    }

    private static void pushThreadToJar(String siteKey, int n) {
        if (TextUtils.isEmpty(siteKey)) return;
        String[] acts = {
                "setPanThread:" + n,
                "panThreadHubSettings:" + n,
                "downloadThread:" + n,
                "setDownloadThread:" + n
        };
        for (String act : acts) {
            try {
                SiteApi.action(siteKey, act);
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    private static int nearestThread(int v) {
        int best = THREAD_OPTS[0];
        int dist = Math.abs(v - best);
        for (int n : THREAD_OPTS) {
            int d = Math.abs(v - n);
            if (d < dist) {
                dist = d;
                best = n;
            }
        }
        return best;
    }

    // -------------------- UI helpers --------------------

    private static LinearLayout column(Activity a) {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(a, 20);
        root.setPadding(p, p, p, p);
        return root;
    }

    private static TextView label(Activity a, String text, int sp, boolean bold) {
        TextView tv = new TextView(a);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) tv.setTypeface(null, Typeface.BOLD);
        return tv;
    }

    private static TextView actionBtn(Activity a, String text) {
        TextView tv = new TextView(a);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setGravity(Gravity.CENTER);
        tv.setFocusable(true);
        tv.setClickable(true);
        int h = dp(a, 44);
        int m = dp(a, 4);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, h, 1f);
        lp.setMargins(m, m, m, m);
        tv.setLayoutParams(lp);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(a, 6));
        bg.setColor(0xFF2A2A2A);
        tv.setBackground(bg);
        tv.setOnFocusChangeListener((v, has) -> {
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(a, 6));
            g.setColor(has ? 0xFF1565C0 : 0xFF2A2A2A);
            v.setBackground(g);
        });
        return tv;
    }

    private static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    // -------------------- Adapter --------------------

    private static final class LineAdapter extends RecyclerView.Adapter<LineAdapter.Holder> {
        private final List<String> items = new ArrayList<>();
        private int selected = 0;

        void setItems(List<String> data) {
            items.clear();
            if (data != null) items.addAll(data);
            selected = 0;
            notifyDataSetChanged();
        }

        List<String> snapshot() {
            return new ArrayList<>(items);
        }

        void moveSelected(int delta) {
            if (items.isEmpty()) return;
            int to = selected + delta;
            if (to < 0 || to >= items.size()) return;
            String s = items.remove(selected);
            items.add(to, s);
            selected = to;
            notifyDataSetChanged();
        }

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext());
            tv.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp((Activity) parent.getContext(), 48)));
            tv.setGravity(Gravity.CENTER_VERTICAL);
            tv.setPadding(dp((Activity) parent.getContext(), 16), 0, 0, 0);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            tv.setFocusable(true);
            tv.setClickable(true);
            return new Holder(tv);
        }

        @Override
        public void onBindViewHolder(Holder h, int position) {
            TextView tv = (TextView) h.itemView;
            tv.setText((position + 1) + ".  " + items.get(position));
            boolean sel = position == selected;
            tv.setTypeface(null, sel ? Typeface.BOLD : Typeface.NORMAL);
            tv.setBackgroundColor(sel ? 0xFF1E3A5F : Color.TRANSPARENT);
            tv.setOnFocusChangeListener((v, has) -> {
                if (has) {
                    selected = h.getBindingAdapterPosition();
                    notifyDataSetChanged();
                }
            });
            tv.setOnClickListener(v -> {
                selected = h.getBindingAdapterPosition();
                moveSelected(-1);
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class Holder extends RecyclerView.ViewHolder {
            Holder(View itemView) {
                super(itemView);
            }
        }
    }
}
