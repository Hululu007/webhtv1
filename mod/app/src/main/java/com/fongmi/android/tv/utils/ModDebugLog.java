package com.fongmi.android.tv.utils;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import com.github.catvod.utils.Prefers;

/**
 * 魔改调试日志：默认不写 logcat；需要时写入 {@code cache/mod_debug/}，
 * 随应用「清除缓存」一并删除。
 * <p>
 * 打开方式：{@code Prefers.put("mod_debug_log", true)} 或调用 {@link #setEnabled(boolean)}。
 */
public final class ModDebugLog {

    private static final String TAG = "ModDebug";
    private static final String PREF = "mod_debug_log";
    private static final long MAX_BYTES = 2L * 1024 * 1024; // 单文件约 2MB，超出截断
    private static volatile Boolean enabled;
    private static volatile File logFile;

    private ModDebugLog() {
    }

    public static void setEnabled(boolean on) {
        enabled = on;
        try {
            Prefers.put(PREF, on);
        } catch (Throwable ignored) {
        }
    }

    public static boolean isEnabled() {
        if (enabled != null) return enabled;
        try {
            enabled = Prefers.getBoolean(PREF);
        } catch (Throwable t) {
            enabled = false;
        }
        return enabled;
    }

    /** 初始化输出路径（建议 Application.onCreate 调一次，不调也能惰性创建） */
    public static void init(Context context) {
        if (context == null) return;
        try {
            File dir = new File(context.getCacheDir(), "mod_debug");
            // noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            logFile = new File(dir, "mod.log");
        } catch (Throwable ignored) {
        }
    }

    public static void i(String tag, String msg) {
        write("I", tag, msg);
    }

    public static void w(String tag, String msg) {
        write("W", tag, msg);
    }

    private static void write(String level, String tag, String msg) {
        if (!isEnabled()) return;
        String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date())
                + " " + level + "/" + (tag == null ? TAG : tag) + ": "
                + (msg == null ? "" : msg) + "\n";
        try {
            File f = logFile;
            if (f == null) {
                try {
                    Context ctx = com.fongmi.android.tv.App.get();
                    if (ctx != null) init(ctx);
                    f = logFile;
                } catch (Throwable ignored) {
                }
            }
            if (f == null) return;
            if (f.length() > MAX_BYTES) {
                // 简单截断：过大则删除重建
                // noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
        // 仅在显式打开调试时同步一份到 logcat，默认关闭时这里不会执行
        if ("W".equals(level)) {
            Log.w(tag, msg);
        }
    }
}
