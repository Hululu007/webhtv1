package com.fongmi.android.tv.utils;

import android.text.TextUtils;

import com.fongmi.android.tv.bean.Collect;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.setting.Setting;

import java.util.Locale;

/** Heuristic classifiers for search-result sort preference (speed / pan / music / short). */
public final class PanSiteHelper {

    private static final String[] PAN_KEYS = {
            "网盘", "云盘", "阿里", "夸克", "百度", "迅雷", "uc盘", "uc网盘",
            "天翼", "115", "123盘", "123云", "pikpak", "alist", "openlist",
            "pan", "alipan", "aliyun", "quark", "baidu", "xunlei", "thunder",
            "觅盘", "盘搜", "seed", "推送"
    };

    private static final String[] MUSIC_KEYS = {
            "音乐", "歌手", "歌单", "专辑", "歌词", "mv", "flac", "ape",
            "网易云", "qq音乐", "酷狗", "酷我", "咪咕", "spotify", "music",
            "audio", "有声", "电台", "播客"
    };

    private static final String[] SHORT_KEYS = {
            "短剧", "短篇", "微剧", "竖屏", "小剧场", "mini剧", "短视频剧",
            "shortdrama", "short_drama", "short-drama", "tiktok剧", "抖音剧"
    };

    private PanSiteHelper() {}

    public static boolean isPanSite(Site site) {
        if (site == null) return false;
        return containsAny(site.getName(), PAN_KEYS)
                || containsAny(site.getKey(), PAN_KEYS)
                || containsAny(site.getApi(), PAN_KEYS);
    }

    public static boolean isPanVod(Vod vod) {
        if (vod == null) return false;
        if (isPanSite(vod.getSite())) return true;
        return containsAny(vod.getSiteName(), PAN_KEYS) || containsAny(vod.getSiteKey(), PAN_KEYS);
    }

    public static boolean isPanCollect(Collect collect) {
        if (collect == null || collect.getSite() == null) return false;
        return isPanSite(collect.getSite());
    }

    public static boolean isMusicSite(Site site) {
        if (site == null) return false;
        return containsAny(site.getName(), MUSIC_KEYS)
                || containsAny(site.getKey(), MUSIC_KEYS)
                || containsAny(site.getApi(), MUSIC_KEYS);
    }

    public static boolean isMusicVod(Vod vod) {
        if (vod == null) return false;
        if (isMusicSite(vod.getSite())) return true;
        return containsAny(vod.getSiteName(), MUSIC_KEYS)
                || containsAny(vod.getSiteKey(), MUSIC_KEYS)
                || containsAny(vod.getName(), MUSIC_KEYS)
                || containsAny(vod.getTypeName(), MUSIC_KEYS);
    }

    public static boolean isMusicCollect(Collect collect) {
        if (collect == null || collect.getSite() == null) return false;
        return isMusicSite(collect.getSite());
    }

    public static boolean isShortSite(Site site) {
        if (site == null) return false;
        return containsAny(site.getName(), SHORT_KEYS)
                || containsAny(site.getKey(), SHORT_KEYS)
                || containsAny(site.getApi(), SHORT_KEYS);
    }

    public static boolean isShortVod(Vod vod) {
        if (vod == null) return false;
        if (isShortSite(vod.getSite())) return true;
        return containsAny(vod.getSiteName(), SHORT_KEYS)
                || containsAny(vod.getSiteKey(), SHORT_KEYS)
                || containsAny(vod.getName(), SHORT_KEYS)
                || containsAny(vod.getTypeName(), SHORT_KEYS)
                || containsAny(vod.getRemarks(), SHORT_KEYS);
    }

    public static boolean isShortCollect(Collect collect) {
        if (collect == null || collect.getSite() == null) return false;
        return isShortSite(collect.getSite());
    }

    /** Match current search-result prefer mode (网盘/音乐/短剧). Speed mode always false. */
    public static boolean matchesSearchPrefer(Vod vod) {
        int mode = Setting.getSearchResultPrefer();
        if (mode == Setting.SEARCH_PREFER_PAN) return isPanVod(vod);
        if (mode == Setting.SEARCH_PREFER_MUSIC) return isMusicVod(vod);
        if (mode == Setting.SEARCH_PREFER_SHORT) return isShortVod(vod);
        return false;
    }

    public static boolean matchesSearchPreferSite(Site site) {
        int mode = Setting.getSearchResultPrefer();
        if (mode == Setting.SEARCH_PREFER_PAN) return isPanSite(site);
        if (mode == Setting.SEARCH_PREFER_MUSIC) return isMusicSite(site);
        if (mode == Setting.SEARCH_PREFER_SHORT) return isShortSite(site);
        return false;
    }

    public static boolean matchesSearchPreferCollect(Collect collect) {
        int mode = Setting.getSearchResultPrefer();
        if (mode == Setting.SEARCH_PREFER_PAN) return isPanCollect(collect);
        if (mode == Setting.SEARCH_PREFER_MUSIC) return isMusicCollect(collect);
        if (mode == Setting.SEARCH_PREFER_SHORT) return isShortCollect(collect);
        return false;
    }

    /** @deprecated use containsAny with PAN_KEYS */
    public static boolean isPanText(String text) {
        return containsAny(text, PAN_KEYS);
    }

    private static boolean containsAny(String text, String[] keys) {
        if (TextUtils.isEmpty(text)) return false;
        String t = text.toLowerCase(Locale.ROOT);
        for (String k : keys) {
            if (t.contains(k.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }
}
