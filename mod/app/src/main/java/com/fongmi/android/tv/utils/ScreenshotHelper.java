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
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.Window;

import androidx.annotation.Nullable;
import androidx.media3.ui.PlayerView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 截取播放画面（尽量不含控制栏）。
 * <p>
 * TV 上 4K/HDR 的 SurfaceView 直接 PixelCopy 常得到花屏/绿条（厂商 overlay 缓冲），
 * 优先对 Window 中视频区域做合成后截取，并对过大尺寸缩放，保证可读截图。
 * 不改动渲染模式与解码链路。
 */
public final class ScreenshotHelper {

    /** 长边上限，降低 4K 花屏与内存压力；仍高于常见 1080p 显示 */
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
        View surface = playerView.getVideoSurfaceView();
        if (surface == null) {
            surface = playerView;
        }
        final View target = surface;
        // 等一帧布局稳定再截，避免 0 尺寸
        target.post(() -> doCapture(activity, playerView, target, main, callback));
    }

    private static void doCapture(Activity activity, PlayerView playerView, View target, Handler main, Callback callback) {
        int vw = target.getWidth();
        int vh = target.getHeight();
        if (vw <= 0 || vh <= 0) {
            fail(main, callback, R.string.screenshot_unavailable);
            return;
        }
        int[] size = fitSize(vw, vh);
        int dw = size[0];
        int dh = size[1];

        // 1) TextureView：直接读位图，再必要时缩放
        if (target instanceof TextureView textureView) {
            try {
                Bitmap raw = textureView.getBitmap();
                if (raw != null && !raw.isRecycled() && raw.getWidth() > 0) {
                    Bitmap out = scaleIfNeeded(raw, dw, dh);
                    if (out != raw) {
                        try {
                            raw.recycle();
                        } catch (Throwable ignored) {
                        }
                    }
                    saveAsync(activity, out, callback);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }

        // 2) 优先：Window 合成区域（用户实际看到的画面，规避 4K Surface overlay 花屏）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                Window window = activity.getWindow();
                if (window != null && window.peekDecorView() != null) {
                    int[] loc = new int[2];
                    target.getLocationInWindow(loc);
                    Rect src = new Rect(loc[0], loc[1], loc[0] + vw, loc[1] + vh);
                    // 略向内收 1px，减少边缘合成噪声
                    if (src.width() > 4 && src.height() > 4) {
                        src.inset(1, 1);
                    }
                    Bitmap bitmap = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888);
                    PixelCopy.request(window, src, bitmap, copyResult -> {
                        if (copyResult == PixelCopy.SUCCESS && !isLikelyGarbage(bitmap)) {
                            saveAsync(activity, bitmap, callback);
                        } else {
                            try {
                                bitmap.recycle();
                            } catch (Throwable ignored) {
                            }
                            // 再试 SurfaceView
                            captureSurfaceFallback(activity, target, dw, dh, main, callback);
                        }
                    }, main);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }

        captureSurfaceFallback(activity, target, dw, dh, main, callback);
    }

    private static void captureSurfaceFallback(Activity activity, View target, int dw, int dh, Handler main, Callback callback) {
        if (!(target instanceof SurfaceView surfaceView) || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            fail(main, callback, R.string.screenshot_fail);
            return;
        }
        try {
            Bitmap bitmap = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888);
            PixelCopy.request(surfaceView, bitmap, copyResult -> {
                if (copyResult != PixelCopy.SUCCESS || isLikelyGarbage(bitmap)) {
                    try {
                        bitmap.recycle();
                    } catch (Throwable ignored) {
                    }
                    fail(main, callback, R.string.screenshot_fail);
                    return;
                }
                saveAsync(activity, bitmap, callback);
            }, main);
        } catch (Throwable e) {
            fail(main, callback, R.string.screenshot_fail);
        }
    }

    /** 长边限制，保持宽高比 */
    private static int[] fitSize(int w, int h) {
        if (w <= 0 || h <= 0) return new int[]{1, 1};
        int max = Math.max(w, h);
        if (max <= MAX_EDGE) return new int[]{w, h};
        float scale = MAX_EDGE / (float) max;
        int nw = Math.max(1, Math.round(w * scale));
        int nh = Math.max(1, Math.round(h * scale));
        return new int[]{nw, nh};
    }

    private static Bitmap scaleIfNeeded(Bitmap src, int dw, int dh) {
        if (src.getWidth() == dw && src.getHeight() == dh) return src;
        if (src.getWidth() <= MAX_EDGE && src.getHeight() <= MAX_EDGE && dw >= src.getWidth()) {
            return src;
        }
        try {
            return Bitmap.createScaledBitmap(src, dw, dh, true);
        } catch (Throwable e) {
            return src;
        }
    }

    /**
     * 粗检：4K 花屏常见为大面积纯绿/近纯色噪声。
     * 采样失败不拦截。
     */
    private static boolean isLikelyGarbage(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return true;
        try {
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            if (w < 8 || h < 8) return true;
            int[] xs = {w / 4, w / 2, 3 * w / 4};
            int[] ys = {h / 4, h / 2, 3 * h / 4};
            int greenish = 0;
            int total = 0;
            for (int y : ys) {
                for (int x : xs) {
                    int c = bitmap.getPixel(x, y);
                    int r = (c >> 16) & 0xff;
                    int g = (c >> 8) & 0xff;
                    int b = c & 0xff;
                    total++;
                    // 高绿、低红蓝：典型绿幕花屏
                    if (g > 180 && g > r + 40 && g > b + 40) greenish++;
                }
            }
            return total > 0 && greenish * 2 >= total; // ≥半数采样点异常绿
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
        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        Uri uri = activity.getContentResolver().insert(collection, values);
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
