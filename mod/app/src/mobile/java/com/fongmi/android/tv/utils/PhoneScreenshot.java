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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
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
            if (frame != null && !isBad(frame)) {
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

            Bitmap bitmap = null;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    // 优先获取与时间点最接近的帧（可能触发软解/平台解码一次）
                    bitmap = retriever.getFrameAtTime(posUs, MediaMetadataRetriever.OPTION_CLOSEST);
                }
                if (bitmap == null) {
                    bitmap = retriever.getFrameAtTime(posUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                }
                if (bitmap == null) {
                    bitmap = retriever.getFrameAtTime(posUs);
                }
            } catch (Throwable ignored) {
            }
            if (bitmap == null) return null;

            // 若能读到元数据宽高且明显更大，尝试按元数据尺寸理解（帧本身已是解码输出）
            try {
                String w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                String h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                if (w != null && h != null) {
                    int mw = Integer.parseInt(w);
                    int mh = Integer.parseInt(h);
                    if (mw > 0 && mh > 0 && (bitmap.getWidth() < mw || bitmap.getHeight() < mh)) {
                        // 帧已经是解码结果，一般等于或接近；不强制放大
                    }
                }
            } catch (Throwable ignored) {
            }
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

        int[] video = resolveVideoSize(playerView);
        int[] target = pickTargetSize(surfW, surfH, video);
        int srcW = target[0];
        int srcH = target[1];

        int[] full = fit(srcW, srcH, MAX_EDGE);
        int[] mid = fit(srcW, srcH, Math.min(MAX_EDGE, Math.max(srcW, srcH) * 3 / 4));
        int[] low = fit(srcW, srcH, Math.min(1920, Math.max(srcW, srcH)));
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
