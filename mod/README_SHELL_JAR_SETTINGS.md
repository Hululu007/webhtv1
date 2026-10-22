# SHELL_JAR_SETTINGS_REDO v3

## 根因（来自 feimao jar 反编译）
- 线路排序在 `Bh` / `Z8` 里用 `ListView` + 行内上/下箭头
- 弹窗依赖 `Init.activityForDialog()`；上游壳子 Init 只有 `set/context`，TV 上焦点易异常
- 列表项 id：`playLineOrderSettings` / `panThreadHubSettings` / `panLoginHubSettings`
- 顺序存储键：`playLineOrder`（逗号分隔）

## 壳子改动
1. `catvod/Init.java`：增加 `activityForDialog` / `setActivity`
2. `App` 生命周期：resume 时 `Init.setActivity(activity)`
3. `SiteApi.detailContent` + `action`：拦截上述 id，打开壳子原生面板
4. 线路排序读写 `Prefers` 键 `playLineOrder`（与 jar 一致）

## 回退
删 `hooks/inject_jar_settings_shell.py`、`JarPanSettingsShell.java`，恢复上游 `Init.java` 后重建。
