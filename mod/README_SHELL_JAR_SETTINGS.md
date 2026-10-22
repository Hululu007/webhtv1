# SHELL_JAR_SETTINGS_REDO

## 做什么
用壳子原生 Dialog + RecyclerView 重做综合设置里：
- 线路排序 (`playLineOrderSettings`)
- 下载线程 (`panThreadHubSettings`)

不再依赖 jar 内 ListView/ScrollView 弹窗（TV 遥控器焦点问题）。

## 数据
- 优先 `SiteApi.action(siteKey, …)` 读写（多种 action 名尝试）
- 失败则用 `Prefers` 本地保存，保证 UI 可用

## 回退
1. 删除 `mod/hooks/inject_jar_settings_shell.py`
2. 删除 `mod/app/src/main/java/com/fongmi/android/tv/ui/dialog/JarPanSettingsShell.java`
3. 从 `apply.sh` 去掉对应段（可选）
4. 重新构建

标记：`SHELL_JAR_SETTINGS_REDO`
