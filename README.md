# ConfigBox

ConfigBox 是一款轻量级的 Android YAML 配置文件模块化拆分与可视化管理工具（Kotlin + Jetpack Compose + Material 3），适合管理结构复杂、需要分块维护的多段式 YAML 配置文件。

## 核心特性

- **初次导入自动拆分**：支持从本地 `.yaml / .yml` 文件、远程链接或剪贴板导入配置，自动将单一大文件拆分为独立模块持久化保存：
  - `base.yaml`（全局基础参数）
  - `proxies.yaml` & `custom_proxies.yaml`（远端同步条目与本地自定义条目分离存储，无损保留扩展字段）
  - `groups.yaml`（分组配置）
  - `rules.json` & `rule_providers.yaml`（可开关、可调整优先级的匹配规则）
- **远端增量更新**：刷新远端配置时仅更新远端来源条目，保留本地自定义条目、分组、规则及链路绑定参数。
- **可视化分块编辑**：
  - **条目管理**：支持按类型筛选、URI 快捷导入、基础字段编辑与原始 YAML 无损编辑；
  - **链路绑定**：支持为目标条目直接绑定前置出口（`dialer-proxy`），不生成多余副本；
  - **分组管理**：支持新建、编辑、排序与删除分组，内置分类 Emoji 快捷选择；
  - **规则管理**：支持搜索、单条启停、上下调整优先级与目标分组绑定。
- **导出与本机局域网同步**：支持导出编译后的完整 `.yaml` 文件、一键复制，或开启本机 HTTP 服务（`http://127.0.0.1:18080/sub/<profileId>.yaml`）供本地应用直接拉取。

## 云端自动构建 APK（GitHub Actions）

仓库已内置 `.github/workflows/build-apk.yml`：

1. 将项目文件推送至 `main` 分支后，自动触发单元测试与 Debug APK 编译。
2. 在仓库 **Actions → Build Android APK** 完成后的 **Artifacts** 区域下载 `ConfigBox-debug-apk` 即可安装。

## 本地构建

1. 使用 Android Studio（2024.2+，JDK 17）打开项目根目录。
2. 执行 `gradle testDebugUnitTest` 运行单元测试，或直接构建安装。
