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
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
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
 * 仅 mobile / 手机使用。与 TV 的 {@code TvScreenshot} 完全独立。
 * 优先按播放器报告的视频分辨率截图（长边上限 3840≈4K），PNG。
 * 说明：PixelCopy 仍来自当前 Surface，若屏上只渲染了较低分辨率，放大后清晰度不会超过实际渲染。
 */
public final class PhoneScreenshot {

    private static final int MAX_EDGE = 3840;

    public interface Callback {
        void onSuccess(Uri uri);

        void onError(String message);
    }

    private PhoneScreenshot() {
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
        surfaceView.post(() -> doCapture(activity, playerView, surfaceView, main, callback));
    }

    private static void doCapture(Activity activity, PlayerView playerView, View view, Handler main, Callback callback) {
        if (view instanceof TextureView textureView) {
            try {
                Bitmap raw = textureView.getBitmap();
                if (raw != null && raw.getWidth() > 0) {
                    int[] video = resolveVideoSize(playerView);
                    Bitmap out = raw;
                    if (video[0] > 0 && video[1] > 0) {
                        int[] target = fit(video[0], video[1], MAX_EDGE);
                        if (target[0] != raw.getWidth() || target[1] != raw.getHeight()) {
                            try {
                                out = Bitmap.createScaledBitmap(raw, target[0], target[1], true);
                            } catch (Throwable ignored) {
                                out = scaleToMax(raw, MAX_EDGE);
                            }
                        }
                    } else {
                        out = scaleToMax(raw, MAX_EDGE);
                    }
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
        int surfW = fw > 0 ? fw : vw;
        int surfH = fh > 0 ? fh : vh;

        // 优先用播放器报告的「视频分辨率」作为截图目标尺寸（上限 4K）
        int[] video = resolveVideoSize(playerView);
        int srcW = surfW;
        int srcH = surfH;
        if (video[0] > 0 && video[1] > 0) {
            srcW = video[0];
            srcH = video[1];
        }

        int[] full = fit(srcW, srcH, MAX_EDGE);
        int[] mid = fit(srcW, srcH, 2560);
        int[] low = fit(srcW, srcH, 1920);
        ArrayList<int[]> list = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (int[] s : new int[][]{full, mid, low}) {
            String k = s[0] + "x" + s[1];
            if (seen.add(k)) list.add(s);
        }
        tryCopy(activity, surfaceView, surface, list.toArray(new int[0][]), 0, main, callback);
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

    private static boolean isBad(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return true;
        try {
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            if (w < 8 || h < 8) return true;
            int green = 0, dark = 0, total = 0;
            for (int yi = 1; yi <= 4; yi++) {
                for (int xi = 1; xi <= 4; xi++) {
                    int c = bitmap.getPixel(w * xi / 5, h * yi / 5);
                    int r = (c >> 16) & 0xff;
                    int g = (c >> 8) & 0xff;
                    int b = c & 0xff;
                    total++;
                    if (r < 18 && g < 18 && b < 18) dark++;
                    if (g > 180 && g > r + 40 && g > b + 40) green++;
                }
            }
            if (total == 0) return true;
            if (dark * 3 >= total * 2) return true;
            if (green * 2 >= total) return true;
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
