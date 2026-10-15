package com.fongmi.android.tv.subtitle;

import android.content.Context;
import android.graphics.Typeface;
import android.util.Log;

import com.fongmi.android.tv.App;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内置字幕字体：system / jiangcheng / youyuan / kaiti（assets/fonts/）。
 * Mobile / Leanback / Exo / MPV 共用。
 * index 0 = 系统默认（无自定义文件），便于对比是否生效。
 */
public final class SubtitleFontManager {

    private static final String TAG = "SubtitleFont";
    public static final String ID_SYSTEM = "system";
    public static final String ID_JIANGCHENG = "jiangcheng";
    public static final String ID_YOUYUAN = "youyuan";
    public static final String ID_KAITI = "kaiti";

    // 0 系统默认, 1 江城, 2 幼圆(默认), 3 楷体
    private static final String[] IDS = {ID_SYSTEM, ID_JIANGCHENG, ID_YOUYUAN, ID_KAITI};
    private static final String[] LABELS_ZH = {"系统默认", "江城圆体", "幼圆", "楷体"};
    private static final String[] LABELS_EN = {"System", "JiangCheng", "YouYuan", "KaiTi"};

    private static final ConcurrentHashMap<String, Typeface> TYPEFACE_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, File> FILE_CACHE = new ConcurrentHashMap<>();

    private SubtitleFontManager() {
    }

    public static int count() {
        return IDS.length;
    }

    public static String idOf(int index) {
        int i = normalizeIndex(index);
        return IDS[i];
    }

    public static int indexOfId(String id) {
        if (id == null) return 0;
        String s = id.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < IDS.length; i++) {
            if (IDS[i].equals(s)) return i;
        }
        // 系统默认
        if (s.contains("system") || s.contains("default") || s.contains("sans") || "系统".equals(s) || "系统默认".equals(s)) {
            return 0;
        }
        // 旧配置兼容（旧 3 档：0江城 1幼圆 2楷体 → 新 +1）
        if (s.contains("kai") || s.contains("cursive")) return 3;
        if (s.contains("you") || s.contains("yuan")) return 2;
        if (s.contains("jiang") || s.contains("cheng") || s.contains("江城") || s.contains("hei") || s.contains("yahei")) return 1;
        if (s.contains("song") || s.contains("serif") || s.contains("mono") || s.contains("fang")) return 1;
        if ("0".equals(s)) return 1;
        if ("1".equals(s)) return 2;
        if ("2".equals(s)) return 3;
        return 0;
    }

    public static int normalizeIndex(int index) {
        if (index < 0 || index >= IDS.length) return 0;
        return index;
    }

    public static String labelOf(int index, boolean zh) {
        int i = normalizeIndex(index);
        return zh ? LABELS_ZH[i] : LABELS_EN[i];
    }

    /**
     * 显示名，用于 ASS FontName / MPV sub-font 查找。
     * 系统默认返回 sans-serif，让 MPV 走内置默认。
     */
    public static String displayNameOf(int index) {
        if (normalizeIndex(index) == 0) return "sans-serif";
        return labelOf(index, true);
    }

    public static boolean isSystem(int index) {
        return normalizeIndex(index) == 0;
    }

    public static Typeface getTypeface(int index) {
        String id = idOf(index);
        Typeface cached = TYPEFACE_CACHE.get(id);
        if (cached != null) return cached;

        // 系统默认：直接用 Android 无衬线，不读 assets
        if (ID_SYSTEM.equals(id)) {
            Typeface tf = Typeface.SANS_SERIF;
            TYPEFACE_CACHE.put(id, tf);
            Log.i(TAG, "using system Typeface.SANS_SERIF");
            return tf;
        }

        Typeface tf = loadTypeface(id);
        if (tf == null && !ID_JIANGCHENG.equals(id)) {
            Log.w(TAG, "load " + id + " failed, fallback to jiangcheng");
            tf = loadTypeface(ID_JIANGCHENG);
        }
        if (tf == null) {
            Log.w(TAG, "load jiangcheng failed, fallback to SANS_SERIF");
            tf = Typeface.SANS_SERIF;
        }
        if (tf != null) TYPEFACE_CACHE.put(id, tf);
        return tf;
    }

    /**
     * 返回可被 MPV/libass 读取的字体文件路径；系统默认返回 null。
     */
    public static File getFontFile(int index) {
        String id = idOf(index);
        if (ID_SYSTEM.equals(id)) return null;
        File cached = FILE_CACHE.get(id);
        if (cached != null && cached.isFile()) return cached;
        File f = ensureFontFile(id);
        if (f == null || !f.isFile()) {
            if (!ID_JIANGCHENG.equals(id)) {
                Log.w(TAG, "font file " + id + " missing, try jiangcheng");
                f = ensureFontFile(ID_JIANGCHENG);
            }
        }
        if (f != null && f.isFile()) {
            FILE_CACHE.put(id, f);
            return f;
        }
        Log.w(TAG, "font file unavailable for " + id);
        return null;
    }

    public static File getFontsDir() {
        try {
            File dir = new File(App.get().getFilesDir(), "subtitle_fonts");
            if (!dir.exists()) dir.mkdirs();
            return dir;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 预解压全部内置字体到 filesDir，供 MPV sub-fonts-dir（跳过 system） */
    public static void prepareAllFonts() {
        for (String id : IDS) {
            if (ID_SYSTEM.equals(id)) continue;
            try {
                ensureFontFile(id);
            } catch (Throwable e) {
                Log.w(TAG, "prepare " + id + ": " + e.getMessage());
            }
        }
        try {
            createAliases();
        } catch (Throwable e) {
            Log.w(TAG, "aliases: " + e.getMessage());
        }
    }

    /** libass 常按中文族名查找：复制别名文件 江城圆体.ttf / 幼圆.ttf / 楷体.ttf */
    private static void createAliases() {
        File dir = getFontsDir();
        if (dir == null) return;
        String[][] pairs = new String[][]{
                {ID_JIANGCHENG, "江城圆体"},
                {ID_YOUYUAN, "幼圆"},
                {ID_KAITI, "楷体"},
                {ID_JIANGCHENG, "JiangCheng"},
                {ID_JIANGCHENG, "JiangChengYuan"},
                {ID_YOUYUAN, "YouYuan"},
                {ID_KAITI, "KaiTi"},
                {ID_KAITI, "STKaiti"},
        };
        for (String[] p : pairs) {
            File src = ensureFontFile(p[0]);
            if (src == null || !src.isFile()) continue;
            File dst = new File(dir, p[1] + ".ttf");
            if (dst.isFile() && dst.length() == src.length()) continue;
            try (java.io.FileInputStream in = new java.io.FileInputStream(src);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } catch (Throwable e) {
                Log.w(TAG, "alias " + p[1] + ": " + e.getMessage());
            }
        }
    }

    private static Typeface loadTypeface(String id) {
        if (ID_SYSTEM.equals(id)) return Typeface.SANS_SERIF;
        try {
            Context ctx = App.get();
            if (ctx == null) return null;
            // 1) assets
            for (String assetPath : assetCandidates(id)) {
                try {
                    Typeface tf = Typeface.createFromAsset(ctx.getAssets(), assetPath);
                    if (tf != null) {
                        Log.i(TAG, "loaded typeface from assets/" + assetPath);
                        return tf;
                    }
                } catch (Throwable ignored) {
                }
            }
            // 2) extracted file
            File f = ensureFontFile(id);
            if (f != null && f.isFile()) {
                Typeface tf = Typeface.createFromFile(f);
                if (tf != null) {
                    Log.i(TAG, "loaded typeface from file " + f.getAbsolutePath());
                    return tf;
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "loadTypeface " + id + ": " + e.getMessage());
        }
        return null;
    }

    private static String[] assetCandidates(String id) {
        return new String[]{
                "fonts/" + id + ".ttf",
                "fonts/" + id + ".otf",
                "fonts/" + id + ".TTC",
                "fonts/" + id + ".ttc",
                "fonts/" + id,
                id + ".ttf",
                id + ".otf",
                id
        };
    }

    private static File ensureFontFile(String id) {
        if (ID_SYSTEM.equals(id)) return null;
        try {
            File dir = getFontsDir();
            if (dir == null) return null;
            // already extracted?
            for (String ext : new String[]{".ttf", ".otf", ".ttc", ""}) {
                File existing = new File(dir, id + ext);
                if (existing.isFile() && existing.length() > 1000) return existing;
            }
            Context ctx = App.get();
            if (ctx == null) return null;
            for (String assetPath : assetCandidates(id)) {
                try (InputStream in = ctx.getAssets().open(assetPath)) {
                    String name = assetPath.contains(".") ? new File(assetPath).getName() : id + ".ttf";
                    if (!name.contains(".")) name = id + ".ttf";
                    File out = new File(dir, name);
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                        fos.flush();
                    }
                    if (out.isFile() && out.length() > 1000) {
                        Log.i(TAG, "extracted " + assetPath + " -> " + out.getAbsolutePath());
                        return out;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "ensureFontFile " + id + ": " + e.getMessage());
        }
        return null;
    }
}
