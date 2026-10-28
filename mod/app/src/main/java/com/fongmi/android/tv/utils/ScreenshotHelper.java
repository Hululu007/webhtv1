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
 * 只截视频 surface，不含状态栏/控制栏。
 * <p>
 * 不用 Window 合成截图：硬件 overlay 视频层在 Window 截图里是黑的，还会带上系统栏。
 * SurfaceView 用 PixelCopy(Surface)；4K 优先「与 surface 同尺寸」，失败再缩小重试。
 */
public final class ScreenshotHelper {

    private static final int MAX_EDGE = 1920;

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
        surfaceView.post(() -> captureView(activity, surfaceView, main, callback));
    }

    private static void captureView(Activity activity, View view, Handler main, Callback callback) {
        if (view instanceof TextureView textureView) {
            try {
                Bitmap raw = textureView.getBitmap();
                if (raw != null && raw.getWidth() > 0 && !raw.isRecycled()) {
                    Bitmap out = maybeDownscale(raw);
                    if (out != raw) {
                        try {
                            raw.recycle();
                        } catch (Throwable ignored) {
                        }
                    }
                    if (isMostlyBlack(out) || isLikelyGarbage(out)) {
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

        // 尝试顺序：原尺寸（上限 MAX_EDGE）→ 更小一档，避开部分机型缩放 PixelCopy 花屏
        int[][] tries = buildTrySizes(srcW, srcH);
        tryPixelCopyChain(activity, surfaceView, surface, tries, 0, main, callback);
    }

    private static int[][] buildTrySizes(int srcW, int srcH) {
        int[] full = fitSize(srcW, srcH, MAX_EDGE);
        int[] half = fitSize(srcW, srcH, Math.min(MAX_EDGE, 1280));
        if (full[0] == half[0] && full[1] == half[1]) {
            return new int[][]{full};
        }
        return new int[][]{full, half};
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
            if (copyResult != PixelCopy.SUCCESS
                    || isLikelyGarbage(bitmap)
                    || isMostlyBlack(bitmap)) {
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
            // 优先 Surface（真实视频缓冲）；部分机型 SurfaceView 更稳，失败链会再试尺寸
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                PixelCopy.request(surface, bitmap, listener, main);
            } else {
                PixelCopy.request(surfaceView, bitmap, listener, main);
            }
        } catch (Throwable e) {
            try {
                // 回退 SurfaceView 入口
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

    private static Bitmap maybeDownscale(Bitmap src) {
        int max = Math.max(src.getWidth(), src.getHeight());
        if (max <= MAX_EDGE) return src;
        int[] s = fitSize(src.getWidth(), src.getHeight(), MAX_EDGE);
        try {
            return Bitmap.createScaledBitmap(src, s[0], s[1], true);
        } catch (Throwable e) {
            return src;
        }
    }

    /** 大面积近黑：Window/overlay 截空常见 */
    private static boolean isMostlyBlack(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return true;
        try {
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            if (w < 4 || h < 4) return true;
            int[] xs = {w / 5, w / 2, 4 * w / 5};
            int[] ys = {h / 5, h / 2, 4 * h / 5};
            int dark = 0, total = 0;
            for (int y : ys) {
                for (int x : xs) {
                    int c = bitmap.getPixel(x, y);
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

    /** 大面积高绿：4K Surface 花屏 */
    private static boolean isLikelyGarbage(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return true;
        try {
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            if (w < 8 || h < 8) return true;
            int[] xs = {w / 4, w / 2, 3 * w / 4};
            int[] ys = {h / 4, h / 2, 3 * h / 4};
            int greenish = 0, total = 0;
            for (int y : ys) {
                for (int x : xs) {
                    int c = bitmap.getPixel(x, y);
                    int r = (c >> 16) & 0xff;
                    int g = (c >> 8) & 0xff;
                    int b = c & 0xff;
                    total++;
                    if (g > 180 && g > r + 40 && g > b + 40) greenish++;
                }
            }
            return total > 0 && greenish * 2 >= total;
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
