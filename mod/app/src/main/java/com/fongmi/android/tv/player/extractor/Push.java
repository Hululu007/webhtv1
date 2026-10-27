package com.fongmi.android.tv.player.extractor;

import android.app.Activity;
import android.net.Uri;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.player.Source;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.utils.UrlUtil;

import java.io.IOException;

/**
 * push:// 资源：立即交给 VideoActivity（push_agent）播放。
 * 不再 sleep + 返回空串，避免当前页空地址拖到 TIMEOUT_PLAY（约 15s）才显示「播放地址加载失败」。
 */
public class Push implements Source.Extractor {

    public static final String HANDOFF = "push-handoff";

    @Override
    public boolean match(Uri uri) {
        return "push".equals(UrlUtil.scheme(uri));
    }

    @Override
    public String fetch(String url) throws Exception {
        final String target = url != null && url.startsWith("push://") ? url.substring(7) : (url == null ? "" : url);
        App.post(() -> {
            try {
                Activity a = App.activity();
                if (a == null) return;
                VideoActivity.start(a, target);
            } catch (Throwable ignored) {
            }
        });
        throw new IOException(HANDOFF);
    }

    @Override
    public void stop() {
    }

    @Override
    public void exit() {
    }
}
