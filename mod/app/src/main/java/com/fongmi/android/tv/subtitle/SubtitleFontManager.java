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
 * 内置字幕字体：youyuan / kaiti / jiangcheng400W（幼圆 / 楷体 / 江城圆体）。
 * Mobile / Leanback / Exo / MPV 共用。
 */
public final class SubtitleFontManager {

    private static final String TAG = "SubtitleFont";
    public static final String ID_YOUYUAN = "youyuan";
    public static final String ID_KAITI = "kaiti";
    public static final String ID_JIANGCHENG = "jiangcheng400W";

    private static final String[] IDS = {ID_YOUYUAN, ID_KAITI, ID_JIANGCHENG};
    private static final String[] LABELS_ZH = {"幼圆", "楷体", "江城圆体"};
    private static final String[] LABELS_EN = {"YouYuan", "KaiTi", "JiangCheng"};
    /** ASS / MPV FontName 优先用这些显示名 */
    private static final String[] DISPLAY = {"幼圆", "楷体", "江城圆体"};

    private static final ConcurrentHashMap<String, Typeface> TYPEFACE_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, File> FILE_CACHE = new ConcurrentHashMap<>();

    private SubtitleFontManager() {
    }

    public static int count() {
        return IDS.length;
    }

    public static String idOf(int index) {
        return IDS[normalizeIndex(index)];
    }

    public static int indexOfId(String id) {
        if (id == null) return 0;
        String s = id.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < IDS.length; i++) {
            if (IDS[i].toLowerCase(Locale.ROOT).equals(s)) return i;
        }
        if (s.contains("jiang") || s.contains("cheng") || s.contains("江城")) return 2;
        if (s.contains("kai") || s.contains("cursive") || s.contains("楷")) return 1;
        if (s.contains("you") || s.contains("yuan") || s.contains("幼")) return 0;
        // 旧雅黑 → 幼圆
        if (s.contains("hei") || s.contains("yahei") || s.contains("sans")) return 0;
        if (s.contains("song") || s.contains("serif") || s.contains("fang")) return 0;
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

    public static String displayNameOf(int index) {
        return DISPLAY[normalizeIndex(index)];
    }

    public static Typeface getTypeface(int index) {
        String id = idOf(index);
        Typeface cached = TYPEFACE_CACHE.get(id);
        if (cached != null) return cached;
        Typeface tf = loadTypeface(id);
        if (tf == null && !ID_YOUYUAN.equals(id)) {
            Log.w(TAG, "load " + id + " failed, fallback to youyuan");
            tf = loadTypeface(ID_YOUYUAN);
        }
        if (tf == null) {
            Log.w(TAG, "load youyuan failed, fallback to SANS_SERIF");
            tf = Typeface.SANS_SERIF;
        }
        TYPEFACE_CACHE.put(id, tf);
        return tf;
    }

    public static File getFontFile(int index) {
        return ensureFontFile(idOf(index));
    }

    public static String getFontsDirPath() {
        try {
            File dir = new File(App.get().getFilesDir(), "subtitle_fonts");
            if (!dir.exists()) dir.mkdirs();
            return dir.getAbsolutePath();
        } catch (Throwable e) {
            return "";
        }
    }

    public static void prepareAllFonts() {
        for (String id : IDS) {
            try {
                ensureFontFile(id);
            } catch (Throwable e) {
                Log.w(TAG, "prepare " + id + ": " + e.getMessage());
            }
        }
    }

    private static Typeface loadTypeface(String id) {
        try {
            File f = ensureFontFile(id);
            if (f != null && f.exists() && f.length() > 0) {
                return Typeface.createFromFile(f);
            }
        } catch (Throwable e) {
            Log.w(TAG, "createFromFile " + id + ": " + e.getMessage());
        }
        try {
            Context ctx = App.get();
            for (String name : assetCandidates(id)) {
                try {
                    return Typeface.createFromAsset(ctx.getAssets(), name);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "createFromAsset " + id + ": " + e.getMessage());
        }
        return null;
    }

    private static File ensureFontFile(String id) {
        File cached = FILE_CACHE.get(id);
        if (cached != null && cached.exists() && cached.length() > 0) return cached;
        try {
            File dir = new File(App.get().getFilesDir(), "subtitle_fonts");
            if (!dir.exists()) dir.mkdirs();
            for (String name : fileNameCandidates(id)) {
                File out = new File(dir, name);
                if (out.exists() && out.length() > 0) {
                    FILE_CACHE.put(id, out);
                    return out;
                }
            }
            Context ctx = App.get();
            for (String asset : assetCandidates(id)) {
                try (InputStream in = ctx.getAssets().open(asset)) {
                    String base = asset.contains("/") ? asset.substring(asset.lastIndexOf('/') + 1) : asset;
                    File out = new File(dir, base);
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                    }
                    if (out.length() > 0) {
                        FILE_CACHE.put(id, out);
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

    private static String[] assetCandidates(String id) {
        return new String[]{
                "fonts/" + id + ".ttf",
                "fonts/" + id + ".otf",
                "fonts/" + id,
                "fonts/" + id + ".TTF",
                "fonts/" + id + ".OTF",
        };
    }

    private static String[] fileNameCandidates(String id) {
        return new String[]{id + ".ttf", id + ".otf", id, id + ".TTF", id + ".OTF"};
    }
}
