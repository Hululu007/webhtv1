package com.fongmi.android.tv.bean;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.impl.Diffable;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.ResUtil;
import com.github.catvod.utils.Prefers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** TV 首页功能区：显隐与顺序持久化。id 0点播 1直播 2搜索 3收藏 4推送 6历史 7设置 */
public class HomeButton implements Diffable<HomeButton> {

    private static final String KEY_BUTTON = "home_button";
    private static final String KEY_SORTED = "home_button_sorted";
    private static final String ALL = "6,0,1,2,3,4,7";
    private static List<HomeButton> buttons;

    private final int id;
    private final int resId;

    public static List<HomeButton> all() {
        if (buttons != null) return buttons;
        buttons = new ArrayList<>();
        buttons.add(new HomeButton(0, R.string.home_vod));
        buttons.add(new HomeButton(1, R.string.home_live));
        buttons.add(new HomeButton(2, R.string.home_search));
        buttons.add(new HomeButton(3, R.string.home_keep));
        buttons.add(new HomeButton(4, R.string.home_push));
        buttons.add(new HomeButton(6, R.string.home_history_button));
        buttons.add(new HomeButton(7, R.string.home_setting));
        return buttons;
    }

    public static List<HomeButton> getButtons() {
        return parse(Prefers.getString(KEY_BUTTON, getDefaultButtons()));
    }

    public static List<HomeButton> getVisibleButtons() {
        List<HomeButton> items = new ArrayList<>();
        for (HomeButton button : getButtons()) {
            if (button.getResId() == R.string.home_live && !LiveConfig.hasUrl()) continue;
            items.add(button);
        }
        return items;
    }

    public static List<HomeButton> sortedAll() {
        List<HomeButton> items = parse(Prefers.getString(KEY_SORTED, ALL));
        if (items.size() == all().size()) return items;
        Map<Integer, HomeButton> map = getMap(items);
        for (HomeButton button : all()) if (!map.containsKey(button.getId())) items.add(button);
        return items;
    }

    public static Map<Integer, HomeButton> getButtonsMap() {
        return getMap(getButtons());
    }

    public static Map<Integer, HomeButton> getMap(List<HomeButton> list) {
        Map<Integer, HomeButton> map = new LinkedHashMap<>();
        for (HomeButton button : list) map.put(button.getId(), button);
        return map;
    }

    public static void save(Map<Integer, HomeButton> map) {
        save(KEY_BUTTON, map);
    }

    public static void saveSorted(Map<Integer, HomeButton> map) {
        save(KEY_SORTED, map);
    }

    public static void reset() {
        Prefers.remove(KEY_BUTTON);
        Prefers.remove(KEY_SORTED);
        buttons = null;
    }

    private static void save(String key, Map<Integer, HomeButton> map) {
        List<String> ids = new ArrayList<>();
        for (Integer id : map.keySet()) ids.add(String.valueOf(id));
        Prefers.put(key, TextUtils.join(",", ids));
    }

    private static String getDefaultButtons() {
        List<String> ids = new ArrayList<>();
        if (!Setting.isHomeHistory()) ids.add("6");
        if (!Setting.isHomeVodAutoLoad()) ids.add("0");
        ids.add("1");
        ids.add("2");
        ids.add("3");
        ids.add("4");
        ids.add("7");
        return TextUtils.join(",", ids);
    }

    private static List<HomeButton> parse(String value) {
        List<HomeButton> items = new ArrayList<>();
        Map<Integer, HomeButton> map = getMap(all());
        if (TextUtils.isEmpty(value)) return items;
        for (String part : value.split(",")) {
            try {
                HomeButton button = map.get(Integer.parseInt(part.trim()));
                if (button != null && !items.contains(button)) items.add(button);
            } catch (NumberFormatException ignored) {
            }
        }
        return items;
    }

    public HomeButton(int id, int resId) {
        this.id = id;
        this.resId = resId;
    }

    public int getId() {
        return id;
    }

    public int getResId() {
        return resId;
    }

    public String getName() {
        return ResUtil.getString(resId);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof HomeButton)) return false;
        return getId() == ((HomeButton) obj).getId();
    }

    @Override
    public boolean isSameItem(HomeButton other) {
        return equals(other);
    }

    @Override
    public boolean isSameContent(HomeButton other) {
        return equals(other);
    }
}
