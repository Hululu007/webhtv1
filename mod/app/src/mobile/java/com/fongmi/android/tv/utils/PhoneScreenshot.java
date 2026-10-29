package com.fongmi.android.tv.utils;

import android.app.Activity;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.MediaMetadataRetriever;
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
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.ui.PlayerView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 仅 mobile。截图时优先按当前进度「单独软解导出一帧」（MediaMetadataRetriever），
 * 不改动正在播放的解码器；失败再回退 Surface PixelCopy。
 * PNG；长边上限 3840。
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
        Player player = null;
        try {
            player = playerView.getPlayer();
        } catch (Throwable ignored) {
        }
        final Player p = player;
        // 后台尝试解码器出帧，避免卡 UI
        Task.execute(() -> {
            Bitmap frame = null;
            try {
                frame = tryDecodeFrame(p);
            } catch (Throwable ignored) {
            }
            if (frame != null) {
                Bitmap out = scaleToMax(frame, MAX_EDGE);
                if (out != frame) {
                    try {
                        frame.recycle();
                    } catch (Throwable ignored) {
                    }
                }
                saveAsync(activity, out, callback);
                return;
            }
            if (frame != null) {
                try {
                    frame.recycle();
                } catch (Throwable ignored) {
                }
            }
            // 回退 Surface（主线程）
            main.post(() -> captureSurface(activity, playerView, main, callback));
        });
    }

    /**
     * 独立打开一路 MediaMetadataRetriever，按当前播放进度取最接近帧。
     * 不触碰正在播放的 Exo 解码器；仅截图瞬间耗电/CPU。
     */
    @Nullable
    private static Bitmap tryDecodeFrame(@Nullable Player player) {
        if (player == null) return null;
        MediaItem item;
        long posMs;
        try {
            item = player.getCurrentMediaItem();
            posMs = player.getCurrentPosition();
        } catch (Throwable e) {
            return null;
        }
        if (item == null || item.localConfiguration == null) return null;
        Uri uri = item.localConfiguration.uri;
        if (uri == null) return null;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);
        long posUs = Math.max(0L, posMs) * 1000L;

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            if ("file".equals(scheme) || "content".equals(scheme) || "android.resource".equals(scheme)) {
                retriever.setDataSource(App.get(), uri);
            } else if ("http".equals(scheme) || "https".equals(scheme)) {
                Map<String, String> headers = new HashMap<>();
                try {
                    // 反射读取可能存在的请求头，避免依赖具体 Media3 字段名
                    Object conf = item.localConfiguration;
                    for (String fieldName : new String[]{"httpRequestHeaders", "requestHeaders", "headers"}) {
                        try {
                            java.lang.reflect.Field f = conf.getClass().getField(fieldName);
                            Object v = f.get(conf);
                            if (v instanceof Map) {
                                for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                                    if (e.getKey() != null && e.getValue() != null) {
                                        headers.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                                    }
                                }
                                break;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                } catch (Throwable ignored) {
                }
                retriever.setDataSource(uri.toString(), headers.isEmpty() ? Collections.emptyMap() : headers);
            } else {
                // push / custom scheme 等：Retriever 通常无效
                return null;
            }

            // 片源宽高（优先 metadata，其次播放器 VideoSize）
            int dstW = 0, dstH = 0;
            try {
                String ws = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                String hs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                if (ws != null && hs != null) {
                    dstW = Integer.parseInt(ws);
                    dstH = Integer.parseInt(hs);
                }
            } catch (Throwable ignored) {
            }
            if (dstW <= 0 || dstH <= 0) {
                try {
                    VideoSize vs = player.getVideoSize();
                    if (vs != null && vs.width > 0 && vs.height > 0) {
                        dstW = vs.width;
                        dstH = vs.height;
                    }
                } catch (Throwable ignored) {
                }
            }
            // 长边上限 4K，等比缩放目标尺寸
            if (dstW > 0 && dstH > 0) {
                int[] fitted = fit(dstW, dstH, MAX_EDGE);
                dstW = fitted[0];
                dstH = fitted[1];
            }

            Bitmap bitmap = null;
            // API 27+：按目标分辨率直接解码缩放帧（比先小后放大更锐）
            if (dstW > 0 && dstH > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                try {
                    bitmap = retriever.getScaledFrameAtTime(
                            posUs, MediaMetadataRetriever.OPTION_CLOSEST, dstW, dstH);
                } catch (Throwable ignored) {
                }
                if (bitmap == null) {
                    try {
                        bitmap = retriever.getScaledFrameAtTime(
                                posUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, dstW, dstH);
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (bitmap == null) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        bitmap = retriever.getFrameAtTime(posUs, MediaMetadataRetriever.OPTION_CLOSEST);
                    }
                } catch (Throwable ignored) {
                }
            }
            if (bitmap == null) {
                try {
                    bitmap = retriever.getFrameAtTime(posUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                } catch (Throwable ignored) {
                }
            }
            if (bitmap == null) {
                try {
                    bitmap = retriever.getFrameAtTime(posUs);
                } catch (Throwable ignored) {
                }
            }
            if (bitmap == null) return null;
            return bitmap;
        } catch (Throwable e) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void captureSurface(Activity activity, PlayerView playerView, Handler main, Callback callback) {
        View surfaceView = playerView.getVideoSurfaceView();
        if (surfaceView == null) {
            fail(main, callback);
            return;
        }
        surfaceView.post(() -> doCaptureSurface(activity, playerView, surfaceView, main, callback));
    }

    private static void doCaptureSurface(Activity activity, PlayerView playerView, View view, Handler main, Callback callback) {
        if (view instanceof TextureView textureView) {
            try {
                Bitmap raw = textureView.getBitmap();
                if (raw != null && raw.getWidth() > 0) {
                    Bitmap out = scaleDownOnly(raw, raw.getWidth(), raw.getHeight(), resolveVideoSize(playerView));
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

        int[] video = resolveVideoSize(playerView);
        int[] target = pickTargetSize(surfW, surfH, video);
        int srcW = target[0];
        int srcH = target[1];

        // 固定最高可用分辨率，不因失败降档重试
        int[] full = fit(srcW, srcH, MAX_EDGE);
        tryCopyOnce(activity, surfaceView, surface, full[0], full[1], main, callback);
    }

    private static void tryCopyOnce(
            Activity activity,
            SurfaceView surfaceView,
            Surface surface,
            int dw,
            int dh,
            Handler main,
            Callback callback) {
        Bitmap bitmap;
        try {
            bitmap = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888);
        } catch (Throwable e) {
            fail(main, callback);
            return;
        }

        AtomicBoolean done = new AtomicBoolean(false);
        PixelCopy.OnPixelCopyFinishedListener listener = result -> {
            if (!done.compareAndSet(false, true)) return;
            if (result != PixelCopy.SUCCESS) {
                try {
                    bitmap.recycle();
                } catch (Throwable ignored) {
                }
                fail(main, callback);
                return;
            }
            // 不做黑屏/绿屏检测，避免正常画面被误判
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
                fail(main, callback);
            }
        }
    }

    private static int[] pickTargetSize(int surfW, int surfH, int[] video) {
        int sw = Math.max(1, surfW);
        int sh = Math.max(1, surfH);
        if (video != null && video.length >= 2 && video[0] > 0 && video[1] > 0) {
            if (video[0] <= sw && video[1] <= sh) {
                return fit(video[0], video[1], MAX_EDGE);
            }
        }
        return fit(sw, sh, MAX_EDGE);
    }

    private static Bitmap scaleDownOnly(Bitmap raw, int surfW, int surfH, int[] video) {
        int[] target = pickTargetSize(Math.max(surfW, raw.getWidth()), Math.max(surfH, raw.getHeight()), video);
        int tw = target[0];
        int th = target[1];
        if (raw.getWidth() <= tw && raw.getHeight() <= th) {
            return scaleToMax(raw, MAX_EDGE);
        }
        try {
            return Bitmap.createScaledBitmap(raw, tw, th, true);
        } catch (Throwable e) {
            return scaleToMax(raw, MAX_EDGE);
        }
    }

    private static int[] resolveVideoSize(PlayerView playerView) {
        try {
            if (playerView == null) return new int[]{0, 0};
            Player player = playerView.getPlayer();
            if (player == null) return new int[]{0, 0};
            VideoSize vs = player.getVideoSize();
            if (vs != null && vs.width > 0 && vs.height > 0) {
                return new int[]{vs.width, vs.height};
            }
        } catch (Throwable ignored) {
        }
        return new int[]{0, 0};
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
     * 视频帧常见 limited range (16–235) 被当 full range 会发灰。
     * 检测后拉伸到 0–255，并略提对比，仅用于截图落盘。
     */

    /**
     * 轻度反锐化蒙版：中心略抬，邻域微减，再与原图混合，避免过锐。
     */
    private static Bitmap mildSharpen(Bitmap src) {
        if (src == null || src.isRecycled()) return src;
        try {
            int w = src.getWidth();
            int h = src.getHeight();
            if (w < 3 || h < 3) return src;
            Bitmap out = src.copy(Bitmap.Config.ARGB_8888, true);
            if (out == null) return src;
            int[] srcPx = new int[w * h];
            int[] dstPx = new int[w * h];
            src.getPixels(srcPx, 0, w, 0, 0, w, h);
            System.arraycopy(srcPx, 0, dstPx, 0, srcPx.length);
            for (int y = 1; y < h - 1; y++) {
                int row = y * w;
                for (int x = 1; x < w - 1; x++) {
                    int i = row + x;
                    int c0 = srcPx[i];
                    int a = (c0 >>> 24) & 0xff;
                    int r = 0, g = 0, b = 0;
                    int[] offs = {i - w, i - 1, i, i + 1, i + w};
                    int[] coef = {-1, -1, 5, -1, -1};
                    for (int k = 0; k < 5; k++) {
                        int c = srcPx[offs[k]];
                        int m = coef[k];
                        r += ((c >> 16) & 0xff) * m;
                        g += ((c >> 8) & 0xff) * m;
                        b += (c & 0xff) * m;
                    }
                    int or = (c0 >> 16) & 0xff;
                    int og = (c0 >> 8) & 0xff;
                    int ob = c0 & 0xff;
                    r = clamp((r * 35 + or * 65) / 100);
                    g = clamp((g * 35 + og * 65) / 100);
                    b = clamp((b * 35 + ob * 65) / 100);
                    dstPx[i] = (a << 24) | (r << 16) | (g << 8) | b;
                }
            }
            out.setPixels(dstPx, 0, w, 0, 0, w, h);
            return out;
        } catch (Throwable e) {
            return src;
        }
    }

    private static Bitmap enhanceVideoLevels(Bitmap src) {
        if (src == null || src.isRecycled()) return src;
        try {
            int w = src.getWidth();
            int h = src.getHeight();
            if (w < 2 || h < 2) return src;
            int minL = 255, maxL = 0, samples = 0;
            long sum = 0;
            for (int yi = 1; yi <= 6; yi++) {
                for (int xi = 1; xi <= 6; xi++) {
                    int c = src.getPixel(w * xi / 7, h * yi / 7);
                    int r = (c >> 16) & 0xff;
                    int g = (c >> 8) & 0xff;
                    int b = c & 0xff;
                    int y = (r * 3 + g * 6 + b) / 10;
                    if (y < minL) minL = y;
                    if (y > maxL) maxL = y;
                    sum += y;
                    samples++;
                }
            }
            if (samples == 0) return src;
            int avg = (int) (sum / samples);
            boolean limitedLike = (minL >= 8 && minL <= 40 && maxL <= 245 && maxL >= 180)
                    || (minL >= 12 && maxL - minL < 200 && avg >= 80 && avg <= 180);
            if (!limitedLike && maxL - minL > 40) {
                return applyContrast(src, 1.06f, -6);
            }

            Bitmap out = src.isMutable()
                    ? src
                    : src.copy(Bitmap.Config.ARGB_8888, true);
            if (out == null) return src;
            int[] pixels = new int[w * h];
            out.getPixels(pixels, 0, w, 0, 0, w, h);
            for (int i = 0; i < pixels.length; i++) {
                int c = pixels[i];
                int a = (c >>> 24) & 0xff;
                int r = contrast1(expandSample((c >> 16) & 0xff));
                int g = contrast1(expandSample((c >> 8) & 0xff));
                int b = contrast1(expandSample(c & 0xff));
                pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
            }
            out.setPixels(pixels, 0, w, 0, 0, w, h);
            return out;
        } catch (Throwable e) {
            return src;
        }
    }

    private static int expandSample(int v) {
        int x = (v - 16) * 255 / 219;
        if (x < 0) return 0;
        if (x > 255) return 255;
        return x;
    }

    private static int contrast1(int v) {
        int x = (int) ((v - 128) * 1.08f + 128);
        if (x < 0) return 0;
        if (x > 255) return 255;
        return x;
    }

    private static Bitmap applyContrast(Bitmap src, float factor, int brightness) {
        try {
            Bitmap out = src.isMutable() ? src : src.copy(Bitmap.Config.ARGB_8888, true);
            if (out == null) return src;
            int w = out.getWidth();
            int h = out.getHeight();
            int[] pixels = new int[w * h];
            out.getPixels(pixels, 0, w, 0, 0, w, h);
            for (int i = 0; i < pixels.length; i++) {
                int c = pixels[i];
                int a = (c >>> 24) & 0xff;
                int r = clamp((int) (((c >> 16) & 0xff) * factor + brightness));
                int g = clamp((int) (((c >> 8) & 0xff) * factor + brightness));
                int b = clamp((int) ((c & 0xff) * factor + brightness));
                pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
            }
            out.setPixels(pixels, 0, w, 0, 0, w, h);
            return out;
        } catch (Throwable e) {
            return src;
        }
    }

    private static int clamp(int v) {
        if (v < 0) return 0;
        if (v > 255) return 255;
        return v;
    }


    private static void saveAsync(Activity activity, Bitmap bitmap, @Nullable Callback callback) {
        Task.execute(() -> {
            Uri uri = null;
            String err = null;
            Bitmap toSave = bitmap;
            try {
                // Surface 回退路径也可能是 limited range，统一拉一遍电平
                Bitmap enhanced = enhanceVideoLevels(bitmap);
                if (enhanced != bitmap) {
                    try {
                        bitmap.recycle();
                    } catch (Throwable ignored) {
                    }
                    toSave = enhanced;
                }
                Bitmap sharp = mildSharpen(toSave);
                if (sharp != toSave) {
                    try {
                        toSave.recycle();
                    } catch (Throwable ignored) {
                    }
                    toSave = sharp;
                }
                uri = savePng(activity, toSave);
            } catch (Throwable e) {
                err = e.getMessage();
            } finally {
                try {
                    toSave.recycle();
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
        try {
            try (OutputStream os = activity.getContentResolver().openOutputStream(uri)) {
                if (os == null) throw new IllegalStateException("openOutputStream failed");
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)) {
                    throw new IllegalStateException("compress failed");
                }
                os.flush();
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                activity.getContentResolver().update(uri, done, null, null);
            }
            return uri;
        } catch (Throwable e) {
            // 删除残留，避免 IS_PENDING=1 或损坏条目留在相册
            try {
                activity.getContentResolver().delete(uri, null, null);
            } catch (Throwable ignored) {
            }
            if (e instanceof Exception) throw (Exception) e;
            throw new Exception(e);
        }
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
