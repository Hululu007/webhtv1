package com.fongmi.android.tv.utils;

import android.Manifest;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.impl.PermissionCallback;
import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.crawler.SpiderDebug;
import com.permissionx.guolindev.PermissionX;
import com.github.catvod.utils.Path;

import java.io.File;
import java.util.function.Consumer;

public class PermissionUtil {

    public static void requestAudio(FragmentActivity activity, Consumer<Boolean> callback) {
        PermissionX.init(activity).permissions(Manifest.permission.RECORD_AUDIO).request(new PermissionCallback(callback));
    }

    public static void requestFile(FragmentActivity activity, Consumer<Boolean> callback) {
        if (hasFileAccess(callback) || unavailable(callback)) return;
        SpiderDebug.log("permission", "request file access managerAvailable=%s", Setting.hasFileManager());
        if (Setting.hasFileManager()) PermissionX.init(activity).permissions().requestManageExternalStoragePermissionNow(new PermissionCallback(result -> finishFileRequest(callback, result)));
        else PermissionX.init(activity).permissions(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE).request(new PermissionCallback(result -> finishFileRequest(callback, result)));
    }

    public static void requestFile(Fragment fragment, Consumer<Boolean> callback) {
        if (hasFileAccess(callback) || unavailable(callback)) return;
        SpiderDebug.log("permission", "request file access managerAvailable=%s", Setting.hasFileManager());
        if (Setting.hasFileManager()) PermissionX.init(fragment).permissions().requestManageExternalStoragePermissionNow(new PermissionCallback(result -> finishFileRequest(callback, result)));
        else PermissionX.init(fragment).permissions(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE).request(new PermissionCallback(result -> finishFileRequest(callback, result)));
    }

    public static boolean requiresFileAccess(String url) {
        if (TextUtils.isEmpty(url)) return false;
        Uri uri;
        try {
            uri = Uri.parse(url);
        } catch (Exception e) {
            return true;
        }
        if (!"file".equalsIgnoreCase(uri.getScheme())) return false;
        String path = uri.getPath();
        if (TextUtils.isEmpty(path)) return true;
        return !inside(Path.files(), path) && !inside(Path.cache(), path);
    }

    private static boolean inside(File root, String path) {
        try {
            String rootPath = root.getCanonicalPath();
            String filePath = new File(path).getCanonicalPath();
            return filePath.equals(rootPath) || filePath.startsWith(rootPath + File.separator);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean hasFileAccess(Consumer<Boolean> callback) {
        if (!Setting.hasFileAccess()) return false;
        SpiderDebug.log("permission", "file access already granted");
        if (callback != null) callback.accept(true);
        return true;
    }

    private static boolean unavailable(Consumer<Boolean> callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Setting.hasFileManager()) return false;
        SpiderDebug.log("permission", "file access manager unavailable");
        if (callback != null) callback.accept(false);
        return true;
    }

    private static void finishFileRequest(Consumer<Boolean> callback, boolean result) {
        boolean granted = Setting.hasFileAccess();
        SpiderDebug.log("permission", "file access request result callback=%s granted=%s", result, granted);
        if (callback != null) callback.accept(granted);
    }

    public static void requestNotify(FragmentActivity activity) {
        PermissionX.init(activity).permissions(PermissionX.permission.POST_NOTIFICATIONS).request(new PermissionCallback());
    }
}
