# ConfigBox — Android Clash 配置模块化拆分与中转组管理 App

ConfigBox 是一款独立的 Android 配置拆分与可视化管理工具（Kotlin + Jetpack Compose + Material 3），面向 Mihomo（Clash.Meta）配置生态设计：

- **初次导入自动拆分**：支持从本地 `.yaml/.yml` 文件、远程订阅链接或剪贴板粘贴导入，自动按模块分离保存到独立文件：
  - `base.yaml`（全局端口、DNS、TUN、Sniffer 等基础设置）
  - `proxies.yaml` & `custom_proxies.yaml`（订阅节点与自定义节点分离存储，无损保留 VLESS Reality / WS / gRPC / Hysteria2 等全部协议参数）
  - `groups.yaml`（策略组）
  - `rules.json` & `rule_providers.yaml`（独立可开关、可排序的路由规则）
  - `relays.json`（`dialer-proxy` 节点中转组）
- **订阅更新不覆盖自定义项**：点击「仅更新订阅节点」时，只刷新远程订阅节点，完整保留你手动添加的落地节点、自定义规则以及中转组。
- **节点独立管理**：支持协议筛选、快捷 URI 导入（`socks5://`、`http://`、`vless://`、`trojan://`、`hysteria2://`、`ss://`）、基础字段编辑以及原始 YAML 无损编辑。
- **规则独立管理**：支持搜索、增删改、单条开关、上下移动调整匹配优先级，目标策略可直接选择中转组。
- **Mihomo `dialer-proxy` 节点中转组**：
  - 入口前置支持选择**单个节点**或**现有策略组**（如 `♻️ 自动测速`），支持可选中间跳板节点与多个出口落地节点；
  - 编译时自动克隆出口节点并注入 `dialer-proxy`（原节点保持不动），生成独立 `select` / `url-test` 中转组，并可自动注入主选择策略组；
  - 支持在弹窗中直接预览生成的 `dialer-proxy` YAML 片段。
- **一键导出 & 本机 HTTP 订阅服务**：
  - 支持导出 `.yaml` 文件或复制完整编译结果；
  - 内置轻量级本机 HTTP 订阅服务（`http://127.0.0.1:18080/sub/<profileId>.yaml`），开启后可直接将链接粘贴进 ClashMetaForAndroid / FlClash 订阅。

## 如何直接打包成 APK（无需本地安装 Android Studio）

本项目已内置 GitHub Actions 工作流（`.github/workflows/build-apk.yml`）：

1. 在 GitHub 新建一个仓库，将本压缩包解压后的全部文件一次性上传至 `main` 分支（建议通过 GitHub Web UI 或 `git push` 一次性提交，避免多次触发构建）。
2. 打开仓库顶部的 **Actions** 页面，等待 **Build Android APK** 自动运行完成（会自动执行单元测试并编译 Debug APK）。
3. 在构建详情页底部的 **Artifacts** 中下载 `ConfigBox-debug-apk`，解压安装即可。

## 本地 Android Studio 构建

1. 使用 Android Studio（Ladybug / 2024.2+，JDK 17）打开 `ConfigBox` 目录。
2. 等待 Gradle Sync 完成。
3. 运行 `./gradlew testDebugUnitTest` 验证核心引擎，或直接点击 Run / Build APK。
