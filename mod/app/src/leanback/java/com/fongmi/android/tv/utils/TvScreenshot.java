package com.fongmi.android.tv.utils;

import android.app.Activity;
import android.content.ContentValues;
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
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 仅 leanback / TV 使用。与手机 ScreenshotHelper 完全独立。
 * 固定：Surface 截图，长边 1920 → 1280（约 1080p），PNG 保存（与成功样张一致，清晰度更好）。
 */
public final class TvScreenshot {

    public interface Callback {
        void onSuccess(Uri uri);

        void onError(String message);
    }

    private TvScreenshot() {
    }

    public static void capture(@Nullable Activity activity, @Nullable PlayerView playerView, @Nullable Callback callback) {
        Handler main = new Handler(Looper.getMainLooper());
        if (activity == null || activity.isFinishing() || playerView == null) {
            fail(main, callback);
            return;
        }
        View surfaceView = playerView.getVideoSurfaceView();
        if (surfaceView == null) {
            fail(main, callback);
            return;
        }
        surfaceView.post(() -> doCapture(activity, surfaceView, main, callback));
    }

    private static void doCapture(Activity activity, View view, Handler main, Callback callback) {
        if (view instanceof TextureView textureView) {
            try {
                Bitmap raw = textureView.getBitmap();
                if (raw != null && raw.getWidth() > 0) {
                    Bitmap out = scaleToMax(raw, 1920);
                    if (out != raw) {
                        try {
                            raw.recycle();
                        } catch (Throwable ignored) {
                        }
                    }
                    if (isBad(out)) {
                        try {
                            out.recycle();
                        } catch (Throwable ignored) {
                        }
                        fail(main, callback);
                        return;
                    }
                    saveAsync(activity, out, callback);
                    return;
                }
            } catch (Throwable ignored) {
            }
            fail(main, callback);
            return;
        }

        if (!(view instanceof SurfaceView surfaceView) || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            fail(main, callback);
            return;
        }

        SurfaceHolder holder = surfaceView.getHolder();
        Surface surface = holder != null ? holder.getSurface() : null;
        if (surface == null || !surface.isValid()) {
            fail(main, callback);
            return;
        }

        Rect frame = holder.getSurfaceFrame();
        int fw = frame != null ? Math.max(frame.width(), 0) : 0;
        int fh = frame != null ? Math.max(frame.height(), 0) : 0;
        int vw = Math.max(surfaceView.getWidth(), 1);
        int vh = Math.max(surfaceView.getHeight(), 1);
        int srcW = fw > 0 ? fw : vw;
        int srcH = fh > 0 ? fh : vh;

        // 与 1930 一致：长边 1920（约 1920x1080），失败再 1280
        int[] a = fit(srcW, srcH, 1920);
        int[] b = fit(srcW, srcH, 1280);
        tryCopy(activity, surfaceView, surface, new int[][]{a, b}, 0, main, callback);
    }

    private static void tryCopy(
            Activity activity,
            SurfaceView surfaceView,
            Surface surface,
            int[][] sizes,
            int index,
            Handler main,
            Callback callback) {
        if (index >= sizes.length) {
            fail(main, callback);
            return;
        }
        int dw = sizes[index][0];
        int dh = sizes[index][1];
        Bitmap bitmap;
        try {
            bitmap = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888);
        } catch (Throwable e) {
            tryCopy(activity, surfaceView, surface, sizes, index + 1, main, callback);
            return;
        }

        AtomicBoolean done = new AtomicBoolean(false);
        PixelCopy.OnPixelCopyFinishedListener listener = result -> {
            if (!done.compareAndSet(false, true)) return;
            if (result != PixelCopy.SUCCESS || isBad(bitmap)) {
                try {
                    bitmap.recycle();
                } catch (Throwable ignored) {
                }
                tryCopy(activity, surfaceView, surface, sizes, index + 1, main, callback);
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
                tryCopy(activity, surfaceView, surface, sizes, index + 1, main, callback);
            }
        }
    }

    private static int[] fit(int w, int h, int maxEdge) {
        int max = Math.max(w, h);
        if (max <= maxEdge) return new int[]{Math.max(1, w), Math.max(1, h)};
        float s = maxEdge / (float) max;
        return new int[]{Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s))};
    }

    private static Bitmap scaleToMax(Bitmap src, int maxEdge) {
        int max = Math.max(src.getWidth(), src.getHeight());
        if (max <= maxEdge) return src;
        int[] s = fit(src.getWidth(), src.getHeight(), maxEdge);
        try {
            return Bitmap.createScaledBitmap(src, s[0], s[1], true);
        } catch (Throwable e) {
            return src;
        }
    }

    /**
     * 花屏 / 近全黑 → 不落盘。
     * 4K overlay 上 PixelCopy 常返回「整屏偏绿 + 高频噪点」，PNG 可到 5～6MB。
     */
    private static boolean isBad(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return true;
        try {
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            if (w < 8 || h < 8) return true;

            long sumR = 0, sumG = 0, sumB = 0;
            int green = 0, dark = 0, wild = 0, total = 0;
            int prev = -1;
            // 8x8 网格，覆盖条带状花屏
            for (int yi = 0; yi < 8; yi++) {
                for (int xi = 0; xi < 8; xi++) {
                    int x = w * (xi * 2 + 1) / 16;
                    int y = h * (yi * 2 + 1) / 16;
                    int c = bitmap.getPixel(x, y);
                    int r = (c >> 16) & 0xff;
                    int g = (c >> 8) & 0xff;
                    int b = c & 0xff;
                    total++;
                    sumR += r;
                    sumG += g;
                    sumB += b;
                    if (r < 22 && g < 22 && b < 22) dark++;
                    // 偏绿（含黄绿条）
                    if (g >= r && g >= b && g > 100 && (g - Math.min(r, b)) > 25) green++;
                    if (g > 140 && g > r + 20 && g > b + 20) green++;
                    // 品红/脏噪声
                    if (r > 120 && b > 120 && g < 120) wild++;
                    if (prev >= 0) {
                        int pr = (prev >> 16) & 0xff;
                        int pg = (prev >> 8) & 0xff;
                        int pb = prev & 0xff;
                        int delta = Math.abs(r - pr) + Math.abs(g - pg) + Math.abs(b - pb);
                        if (delta > 200) wild++;
                    }
                    prev = c;
                }
            }
            if (total == 0) return true;
            if (dark * 5 >= total * 3) return true;          // ≥60% 近黑
            if (green * 10 >= total * 3) return true;         // ≥30% 异常绿
            if (wild * 10 >= total * 3) return true;          // ≥30% 噪点跳变

            // 整图平均严重偏绿（用户绿条花屏典型）
            int avgR = (int) (sumR / total);
            int avgG = (int) (sumG / total);
            int avgB = (int) (sumB / total);
            if (avgG > avgR + 30 && avgG > avgB + 30 && avgG > 90) return true;

            // 邻域再扫一行：高频雪花
            int jumps = 0, rowN = 0;
            int y = h / 2;
            int last = bitmap.getPixel(0, y);
            for (int x = w / 32; x < w; x += Math.max(1, w / 32)) {
                int c = bitmap.getPixel(x, y);
                int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
                int lr = (last >> 16) & 0xff, lg = (last >> 8) & 0xff, lb = last & 0xff;
                if (Math.abs(r - lr) + Math.abs(g - lg) + Math.abs(b - lb) > 180) jumps++;
                rowN++;
                last = c;
            }
            if (rowN > 4 && jumps * 2 >= rowN) return true; // 中线一半以上剧烈跳变

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
                else callback.onError(finalErr == null || finalErr.isEmpty() ? msgFail() : finalErr);
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

    private static void fail(Handler main, Callback callback) {
        main.post(() -> {
            if (callback != null) callback.onError(msgFail());
        });
    }

    private static String msgFail() {
        try {
            return App.get().getString(R.string.screenshot_fail);
        } catch (Throwable e) {
            return "error";
        }
    }
}
