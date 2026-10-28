package com.fongmi.android.tv.utils;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.media3.ui.PlayerView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 只截视频 surface，不含状态栏/控制栏。
 * <p>
 * 手机：优先 surface 显示尺寸（可到 2K+）。
 * TV：4K Surface 直接 PixelCopy 常花屏，优先 1920/1280，再尝试更高；不做 Window 截图（会黑屏带状态栏）。
 */
public final class ScreenshotHelper {

    private static final int HARD_CAP = 4096;

    public interface Callback {
        void onSuccess(Uri uri);

        void onError(String message);
    }

    private ScreenshotHelper() {
    }

    public static void capture(@Nullable Activity activity, @Nullable PlayerView playerView, @Nullable Callback callback) {
        Handler main = new Handler(Looper.getMainLooper());
        if (activity == null || activity.isFinishing() || playerView == null) {
            fail(main, callback, R.string.screenshot_unavailable);
            return;
        }
        View surfaceView = playerView.getVideoSurfaceView();
        if (surfaceView == null) {
            fail(main, callback, R.string.screenshot_unavailable);
            return;
        }
        boolean tv = isTelevision(activity);
        surfaceView.post(() -> captureView(activity, surfaceView, tv, main, callback));
    }

    private static boolean isTelevision(Context context) {
        try {
            UiModeManager ui = (UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE);
            if (ui != null && ui.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            PackageManager pm = context.getPackageManager();
            if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                    || pm.hasSystemFeature("android.software.leanback")) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void captureView(Activity activity, View view, boolean tv, Handler main, Callback callback) {
        if (view instanceof TextureView textureView) {
            try {
                Bitmap raw = textureView.getBitmap();
                if (raw != null && raw.getWidth() > 0 && !raw.isRecycled()) {
                    Bitmap out = maybeDownscale(raw, tv);
                    if (out != raw) {
                        try {
                            raw.recycle();
                        } catch (Throwable ignored) {
                        }
                    }
                    if (isBadFrame(out)) {
                        try {
                            out.recycle();
                        } catch (Throwable ignored) {
                        }
                        fail(main, callback, R.string.screenshot_fail);
                        return;
                    }
                    saveAsync(activity, out, callback);
                    return;
                }
            } catch (Throwable ignored) {
            }
            fail(main, callback, R.string.screenshot_fail);
            return;
        }

        if (!(view instanceof SurfaceView surfaceView) || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            fail(main, callback, R.string.screenshot_unavailable);
            return;
        }

        SurfaceHolder holder = surfaceView.getHolder();
        Surface surface = holder != null ? holder.getSurface() : null;
        if (surface == null || !surface.isValid()) {
            fail(main, callback, R.string.screenshot_unavailable);
            return;
        }

        Rect frame = holder.getSurfaceFrame();
        int fw = frame != null ? Math.max(frame.width(), 0) : 0;
        int fh = frame != null ? Math.max(frame.height(), 0) : 0;
        int vw = surfaceView.getWidth();
        int vh = surfaceView.getHeight();
        int srcW = fw > 0 ? fw : vw;
        int srcH = fh > 0 ? fh : vh;
        if (srcW <= 0 || srcH <= 0) {
            fail(main, callback, R.string.screenshot_unavailable);
            return;
        }

        int[][] tries = buildTrySizes(srcW, srcH, tv);
        tryPixelCopyChain(activity, surfaceView, surface, tries, 0, main, callback);
    }

    /**
     * 手机：原尺寸 → 逐步缩小。
     * TV：1920 → 1280 → 960 优先（躲开 4K overlay 花屏）；再试 2560（少数机型可用）。
     */
    private static int[][] buildTrySizes(int srcW, int srcH, boolean tv) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        ArrayList<int[]> list = new ArrayList<>();
        int[] edges;
        if (tv) {
            edges = new int[]{1920, 1280, 960, 2560};
        } else {
            edges = new int[]{HARD_CAP, 3840, 2560, 1920, 1280};
        }
        if (!tv) {
            int[] nativeSize = fitSize(srcW, srcH, HARD_CAP);
            addSize(list, seen, nativeSize);
        }
        for (int edge : edges) {
            addSize(list, seen, fitSize(srcW, srcH, edge));
        }
        return list.toArray(new int[0][]);
    }

    private static void addSize(ArrayList<int[]> list, LinkedHashSet<String> seen, int[] s) {
        String key = s[0] + "x" + s[1];
        if (seen.add(key)) list.add(s);
    }

    private static void tryPixelCopyChain(
            Activity activity,
            SurfaceView surfaceView,
            Surface surface,
            int[][] sizes,
            int index,
            Handler main,
            Callback callback) {
        if (index >= sizes.length) {
            fail(main, callback, R.string.screenshot_fail);
            return;
        }
        int dw = sizes[index][0];
        int dh = sizes[index][1];
        Bitmap bitmap;
        try {
            bitmap = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888);
        } catch (Throwable e) {
            tryPixelCopyChain(activity, surfaceView, surface, sizes, index + 1, main, callback);
            return;
        }

        AtomicBoolean done = new AtomicBoolean(false);
        PixelCopy.OnPixelCopyFinishedListener listener = copyResult -> {
            if (!done.compareAndSet(false, true)) return;
            if (copyResult != PixelCopy.SUCCESS || isBadFrame(bitmap)) {
                try {
                    bitmap.recycle();
                } catch (Throwable ignored) {
                }
                tryPixelCopyChain(activity, surfaceView, surface, sizes, index + 1, main, callback);
                return;
            }
            saveAsync(activity, bitmap, callback);
        };

        try {
            PixelCopy.request(surface, bitmap, listener, main);
        } catch (Throwable e) {
            try {
                PixelCopy.request(surfaceView, bitmap, listener, main);
            } catch (Throwable e2) {
                try {
                    bitmap.recycle();
                } catch (Throwable ignored) {
                }
                tryPixelCopyChain(activity, surfaceView, surface, sizes, index + 1, main, callback);
            }
        }
    }

    private static int[] fitSize(int w, int h, int maxEdge) {
        if (w <= 0 || h <= 0) return new int[]{1, 1};
        int max = Math.max(w, h);
        if (max <= maxEdge) return new int[]{w, h};
        float scale = maxEdge / (float) max;
        return new int[]{Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale))};
    }

    private static Bitmap maybeDownscale(Bitmap src, boolean tv) {
        int cap = tv ? 1920 : HARD_CAP;
        int max = Math.max(src.getWidth(), src.getHeight());
        if (max <= cap) return src;
        int[] s = fitSize(src.getWidth(), src.getHeight(), cap);
        try {
            return Bitmap.createScaledBitmap(src, s[0], s[1], true);
        } catch (Throwable e) {
            return src;
        }
    }

    private static boolean isBadFrame(Bitmap bitmap) {
        return isMostlyBlack(bitmap) || isLikelyGarbage(bitmap);
    }

    private static boolean isMostlyBlack(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return true;
        try {
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            if (w < 4 || h < 4) return true;
            int dark = 0, total = 0;
            for (int yi = 1; yi <= 4; yi++) {
                for (int xi = 1; xi <= 4; xi++) {
                    int c = bitmap.getPixel(w * xi / 5, h * yi / 5);
                    int r = (c >> 16) & 0xff;
                    int g = (c >> 8) & 0xff;
                    int b = c & 0xff;
                    total++;
                    if (r < 18 && g < 18 && b < 18) dark++;
                }
            }
            return total > 0 && dark * 3 >= total * 2;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 花屏：大面积异常绿，或采样点之间反差极大的噪声条带。
     */
    private static boolean isLikelyGarbage(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return true;
        try {
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            if (w < 8 || h < 8) return true;
            int greenish = 0, wild = 0, total = 0;
            int prev = -1;
            for (int yi = 1; yi <= 4; yi++) {
                for (int xi = 1; xi <= 4; xi++) {
                    int c = bitmap.getPixel(w * xi / 5, h * yi / 5);
                    int r = (c >> 16) & 0xff;
                    int g = (c >> 8) & 0xff;
                    int b = c & 0xff;
                    total++;
                    if (g > 160 && g > r + 35 && g > b + 35) greenish++;
                    // 品红/脏噪声
                    if (r > 140 && b > 140 && g < 100) wild++;
                    if (prev >= 0) {
                        int pr = (prev >> 16) & 0xff;
                        int pg = (prev >> 8) & 0xff;
                        int pb = prev & 0xff;
                        int dr = Math.abs(r - pr), dg = Math.abs(g - pg), db = Math.abs(b - pb);
                        if (dr + dg + db > 280) wild++;
                    }
                    prev = c;
                }
            }
            if (total == 0) return true;
            if (greenish * 2 >= total) return true;
            if (wild * 2 >= total) return true;
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    private static void saveAsync(Activity activity, Bitmap bitmap, @Nullable Callback callback) {
        Task.execute(() -> {
            Uri uri = null;
            String err = null;
            try {
                uri = savePng(activity, bitmap);
            } catch (Throwable e) {
                err = e.getMessage();
            } finally {
                try {
                    bitmap.recycle();
                } catch (Throwable ignored) {
                }
            }
            Uri finalUri = uri;
            String finalErr = err;
            App.post(() -> {
                if (callback == null) return;
                if (finalUri != null) callback.onSuccess(finalUri);
                else callback.onError(finalErr == null || finalErr.isEmpty() ? msg(R.string.screenshot_fail) : finalErr);
            });
        });
    }

    private static Uri savePng(Activity activity, Bitmap bitmap) throws Exception {
        String name = "WEBHTV_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".png";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/WEBHTV");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }
        Uri uri = activity.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IllegalStateException("insert failed");
        try (OutputStream os = activity.getContentResolver().openOutputStream(uri)) {
            if (os == null) throw new IllegalStateException("openOutputStream failed");
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)) {
                throw new IllegalStateException("compress failed");
            }
            os.flush();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            activity.getContentResolver().update(uri, values, null, null);
        }
        return uri;
    }

    private static void fail(Handler main, Callback callback, int res) {
        main.post(() -> {
            if (callback != null) callback.onError(msg(res));
        });
    }

    private static String msg(int res) {
        try {
            return App.get().getString(res);
        } catch (Throwable e) {
            return "error";
        }
    }
}
