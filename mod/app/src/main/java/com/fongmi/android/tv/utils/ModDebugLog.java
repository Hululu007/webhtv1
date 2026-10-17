package com.fongmi.android.tv.utils;

import android.content.Context;

import com.fongmi.android.tv.App;
import com.github.catvod.utils.Prefers;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 魔改日志：例行 Info 默认关闭；失败 {@link #w} 始终写入
 * {@code context.getCacheDir()/mod_debug/mod.log}，清缓存即删。不写 logcat。
 */
public final class ModDebugLog {

    private static final String PREF = "mod_debug_log";
    private static final long MAX_BYTES = 2L * 1024 * 1024;
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
        return Boolean.TRUE.equals(enabled);
    }

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
        if (!isEnabled()) return;
        writeForce("I", tag, msg);
    }

    public static void w(String tag, String msg) {
        writeForce("W", tag, msg);
    }

    private static void writeForce(String level, String tag, String msg) {
        String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date())
                + " " + level + "/" + (tag == null ? "ModDebug" : tag) + ": "
                + (msg == null ? "" : msg) + "\n";
        try {
            File f = logFile;
            if (f == null) {
                try {
                    Context ctx = App.get();
                    if (ctx != null) init(ctx);
                    f = logFile;
                } catch (Throwable ignored) {
                }
            }
            if (f == null) return;
            if (f.length() > MAX_BYTES) {
                // noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }
}
