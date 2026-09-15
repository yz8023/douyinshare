# HANDOVER.md — dyparse 项目交接文档

> 本交接包生成时间：2026-09-15
> 交接包还原点 tag：`v4.10`（已推送 GitHub）
> 远程仓库：https://github.com/yz8023/douyinshare
> 交接源码 commit：`3cc3db2`（tag `v4.10` 指向同一 commit）

---

## 1. 项目概述

**一句话定位**：多平台短视频/图集/音乐解析与下载工具 —— Android 客户端（Kotlin / Jetpack Compose）+ PHP 服务端，支持 48 个平台解析：抖音/快手/小红书/bilibili/微博/头条/皮皮虾/皮皮搞笑/网易云/汽水音乐 + 西瓜/好看/知乎/虎牙/绿洲/美拍/全民K歌/新片场/最右/QQ音乐/酷狗/AcFun/微视/梨视频 + 豆包/剪映/即梦AI/夸克AI/通义千问/腾讯频道/腾讯元宝/拼多多/番茄小说/星绘AI/央视/央视频/视频号/公众号 + 可灵/配音秀/松果/快影/得物/Soul/闲鱼/LOFTER/海螺/小云雀 等，含作者批量解析、图集/实况、画质多档、剪贴板监听、悬浮球保活、本地历史与批量分类筛选。

| 项目 | 值 |
| --- | --- |
| 技术栈 | Kotlin 2.0.21、Jetpack Compose（BOM 2024.09.00）、Room 2.7.2、OkHttp 5.3.2、Coil 2.7.0、Media3 1.2.0、NDK C++（a_bogus 原生签名） |
| 构建工具 | AGP 9.0.1、Gradle 9.2.1（wrapper） |
| minSdk / targetSdk / compileSdk | 24 / 34 / 36 |
| 包名 / 应用名 | `Forinxy.jiexi` / `dyparse` |
| ABI | 仅 `arm64-v8a`（`app/build.gradle.kts` abiFilters） |
| 版本 | versionCode 50 / versionName 4.10 |
| 当前 commit | `3cc3db2` |

## 2. 开发环境

| 项 | 值 |
| --- | --- |
| OS | Linux（本项目交接机） |
| JDK | JDK 21（`gradle/gradle-daemon-jvm.properties` toolchainVersion=21；本机位于 `/opt/java21`） |
| Android SDK | compileSdk 36；平台 `android-36`、build-tools 36.0.0；路径 `/opt/android-sdk` |
| NDK | 28.2.13676358 |
| CMake | 3.22.1 |
| 环境变量 | `local.properties` 需含 `sdk.dir`（已被 .gitignore 忽略）；可选用 `DY_PARSE_CN_MIRRORS=true` 切阿里云镜像 |
| 第三方 Key | 仅占位符（`SERVER_API_TOKEN=dyparse_server_token_change_me`、`SERVER_HMAC_KEY=dyparse_hmac_change_me`），真实值由使用者自行注入 `local.properties` 或 CI Secrets |

## 3. 构建运行

```bash
# 从克隆到 debug 包（JDK 21 环境）
export JAVA_HOME=/opt/java21   # 你的机器请指向本机 JDK21
export PATH=$JAVA_HOME/bin:$PATH
cd dyparse
cp local.properties.example local.properties
#    编辑 local.properties 填 sdk.dir（不打正式包时签名区可留空）
./gradlew assembleDebug
#    产物：app/build/outputs/apk/debug/app-debug.apk

# 单元测试
./gradlew test

# 正式包（需签名配置，见第 12 节）
./gradlew assembleRelease
```

**已知坑与解法**：
- 本机 `java` 命令不在 PATH：先 `export JAVA_HOME=/opt/java21`（本交接机），或安装 JDK21。
- 国内拉不动 `dl.google.com`：在 `~/.gradle/gradle.properties` 加 `systemProp.dyparse.cnMirrors=true`，或设环境变量 `DY_PARSE_CN_MIRRORS=true`（settings.gradle.kts 会切阿里云优先）。
- release 未配签名会失败，`assembleDebug` 不受影响。
- 首包构建较慢（含 NDK C++ 编译与 Compose），本交接机约 5-6 分钟，需耐心。

**CI 构建脚本（无需本地环境）**：
- `.github/workflows/build.yml` —— push 到 `main` / PR / 手动触发，自动 assembleDebug 并上传 `app-debug` artifact。
- `.github/workflows/release.yml` —— 推送 `v*` tag 时自动签名 assembleRelease、校验签名、创建 GitHub Release 并挂 APK。触发方式：`git tag v4.4 && git push origin v4.4`（需 Secrets：`RELEASE_KEYSTORE_BASE64`/`RELEASE_STORE_PASSWORD`/`RELEASE_KEY_ALIAS`/`RELEASE_KEY_PASSWORD`）。

## 4. 依赖与镜像

主要依赖来源：Google Maven / Maven Central / gradlePluginPortal（官方源优先），阿里云兜底。镜像映射见 `settings.gradle.kts` 与 README「依赖仓库来源」。

| 官方源 | 镜像地址 |
| --- | --- |
| Google Maven | `https://maven.aliyun.com/repository/google` |
| Maven Central | `https://maven.aliyun.com/repository/central` |
| gradle-plugin portal | `https://maven.aliyun.com/repository/gradle-plugin` |
| public/central 综合 | `https://maven.aliyun.com/repository/public` |
| Gradle 发行版 | wrapper 使用 `services.gradle.org`（`gradle-wrapper.properties`，可用腾讯镜像 `https://mirrors.cloud.tencent.com/gradle/` 替换） |

依赖锁定：`gradle/libs.versions.toml`（KSP 2.0.21、AGP 9.0.1 等）。获取优先级：本地缓存 → 国内镜像 → 官方源（当前就是官方源+镜像兜底）。

## 5. 本地依赖服务

**无需后端/数据库即可运行**：App 内置解析服务器（`BuiltInServer.kt` + 内置 a_bogus 原生签名），不依赖外部服务。可选的 PHP 服务端（`server/` 目录）用于更高成功率的服务端解析：
- 部署 `data.php`、`author_list.php`、`abogus.php`、`config.php` 到同一目录（PHP 8.0+，配置见 `server/README.md`）。
- `config.php` 由 `config.example.php` 复制，必须填 `API_TOKEN`、`API_HMAC_KEY`、`DOUYIN_COOKIE` 并置 `DOUYIN_COOKIE_ENABLED=true`。
- 本地 App 侧：`local.properties` 里填 `SERVER_API_BASE` 等，或运行时在 App「设置 → 服务器配置」填写（存本机，优先级最高）。

## 6. 开发进度

- **已完成**：多画质显示与直链下载、作者批量解析、剪贴板监听与历史页、作者主页引导、图集/实况解析与保存配对、内置解析服务器、批量分类筛选（全部/视频/图集/实况）、图集整本下载、画质多档兜底（原画质/最高画质/1080p~360p）、403 修复（AnonymousSessionStore 兜底 + cookie 预热链路 + flat_images 支持）、多平台解析（48 个平台全部内置本地，抖音存量行为不变）、悬浮球保活（FloatingBallService/View/Preferences + 剪贴板监听 ACTION_POLL_NOW）、多链接批量解析（主页粘贴整段多链接文本自动拆分逐条解析并写历史）、登录 Cookie 管理（复制/导出/导入，支持登录态受限内容如抖音限时日常）。
- **进行中**：无。
- **已搁置**：`server/config.php`（本机未配置，用的是 example 占位，部署在自己服务器时才需要真实值）。
- **最近可运行 commit**：`3cc3db2`（tag `v4.10`）。

## 7. 待开发内容

> 用户当前方向：**把 App 扩展为多平台，支持更多短视频平台解析。**

| 功能 | 优先级 | 预估 | 前置依赖 |
| --- | --- | --- | --- |
| 其余 media-parser 平台接入（豆包 FPLAY 已支持、拼多多 feed 签名受限等剩余子集） | P2 | 中 | 拼多多 anti_signer 依赖 JS 引擎（MiniRacer），JVM 无法移植，仅 SSR/分享图子集 |
| 平台 Cookie 配置入口（如小红书 XHS_COOKIE 提升成功率） | P1 | 中 | 客户端设置页 + 解析器注入 |
| 外部服务端多平台统一 API（参考 short_videos PHP 方案） | P1 | 中 | 确定是否复用/迁移 server/ 目录 |

## 8. 架构与关键模块

- **入口**：`MainActivity.kt`（首页、设置、服务器配置入口）；`DyparseApp`（Application，Room 初始化）。
- **解析管线**：
  - `ParserViewModel.kt`（核心 ViewModel；`saveMedia`、`buildGallerySaveTasks`、`saveBatchMedia`、画质优先级 & hooks，Line 1600-2000 区域）。
  - `LocalParseEngine.kt`（本地解析引擎；`buildQualityList` 多档兜底，Line 183）。
  - `BuiltInParser.kt`（内置解析；`appendFallbackQuality` 多档兜底 Line 637、`buildOriginalPlayEndpointUrl` Line 880）。
  - `DouyinAuthorWebApiBridge.kt` / `AuthorBatchManager.kt`（作者批量解析、detail API Line 261 区域）。
  - `DouyinGalleryMediaResolver.kt`（图集/实况识别：`collectGalleryMedia` 支持 `flat_images`+`flat_live_photos` 平铺结构）。
  - `ServerApiClient.kt` / `ServerAuthorClient.kt`（外部 PHP 服务端客户端，HMAC 签名）。
  - `DouyinABogusSigner.kt` + `NativeLib.kt` + `SecurityGuard.kt`（NDK a_bogus 原生签名）。
  - `FileSaver.kt`（保存到相册；`saveFile` 支持 `subPathOverride`/`extensionOverride`，Line 66；实况配对 `jpg`+同名 `mov`）。
- **数据层**：Room（`data/local/HistoryDatabase.kt`、`HistoryRepository.kt`）、`data/ParseResult.kt`（`type`/`livePhotoCount`/`gallery_media`）、`data/BatchParseModels.kt`。
- **UI**：`ui/page/`（BatchParsePage、ParseItemDetailDialog、MainActivity 首页结果区、ClipboardRecordsPage 等）、`ui/theme/`（Miuix 组件、liquid 玻璃拟态）。
- **前后端对接点**：`server/data.php`（GET `?url=`，`X-Token`/`X-Time`/`X-Sign` 头，HMAC-SHA256）与客户端 `ServerApiClient` 对齐；响应字段 `play_url/raw_play_url/images/gallery_media/live_photo` 等。
- **已知技术债**：
  - 抖音 web 已下架 `download_addr`/`bit_rate`，画质兜底靠 ratio 枚举多档生成 URL，`size_bytes=JsonNull` 需客户端即时探测。
  - `BatchMediaPreviewDialog`（BatchParsePage.kt Line ~1104）为死代码（无调用点），保留未删。
  - ABogus/Cookie 体系随抖音风控变动较敏感，属于易失效点。

## 9. 代码概览与已知问题

- 结构：单 module（`:app`）+ `server/`（PHP），Kotlin 源码 70+ 个文件，Java 侧 `src/main/cpp/native-lib.cpp`（NDK）。单元测试 140 个（`app/src/test`）。
- **致命/严重问题**：
  - `assembleRelease` 需要签名配置，当前仓库/CI 若未配 Secrets 会失败（预期行为，需按第 12 节配置）。
  - 抖音风控升级时匿名 cookie + a_bogus 可能被 403 拦截，已有 3 层兜底；仍需在 `AnonymousSessionStore` / cookie 预热链路处按需调参。
- 其余为告警级（Deprecated API 等），不影响构建。

## 10. 架构简评

- **架构模式**：MVVM（StateFlow + ViewModel）+ 仓库层（Repository/DAO）+ Compose 声明式 UI；内置解析与外部服务端双通道。
- **整体评价**：良好。分层清晰、配置注入安全、镜像与 CI 完备。
- **突出问题与改进方向**：
  1. 多平台已落地为「平台路由 → PlatformParser 接口 → 各平台实现」：`builtin/MultiPlatformParser.kt` 门面 + `builtin/Platform.kt` 域名识别（48 枚举）+ 47 个独立 `PlatformParser` 实现，抖音走既有 `BuiltInParser`（行为不动）。
  2. 微博/小红书等个别平台受网页风控影响成功率波动，可考虑解析器级 Cookie 注入入口。
  3. 拼多多 feed 视频依赖 JS 引擎（anti_content 签名）未移植，仅 SSR rawData / _oak_share_url 子集；腾讯频道 EdgeOne WAF 挑战需 JS 求解未移植，仅提取非挑战页。
  4. 保存链路逐步加参数（`subPathOverride` 等），必要时重构为 `SaveOptions` 值对象，避免参数爆炸。
  5. 内置服务器与外部服务端并存，响应字段有重叠，可抽公共 DTO。
  6. `BatchMediaPreviewDialog` 死代码清理。

## 11. 测试建议

- 核心流程能否走通：本地 `./gradlew test` 通过 140 个单元测试，`assembleDebug`/`assembleRelease` 出包成功；UI 需真机/模拟器验证。
- 重点测试模块/场景：
  - 抖音单条解析（分享链接 / 裸 ID / `/note/` 图集路径），403 兜底链路。
  - 多平台识别与解析：`builtin/Platform.kt` 域名路由 + 各 `PlatformParser` 解析（`PlatformDetectTest` 87 条 + `PlatformIndexTest`）。
  - 多链接批量解析（主页粘贴整段多链接文本，进度/成功写入历史）与 Cookie 管理（复制/导出/导入、导入校验 sessionid）。
  - 悬浮球显示（授权页返回/服务被杀后回前台补启动）与剪贴板读取（Android 10+ 前台才可读，悬浮球为绕开方案）。
  - 图集/实况实现识别与保存配对（`xxx.jpg`+`xxx.mov` 同目录同名）。
  - 批量解析分类筛选（全部/视频/图集/实况）与整本下载。
  - 画质多档（原画质/最高画质/1080p~360p）选择与下载。
  - 作者批量解析、剪贴板监听、历史回放。
- 已知/可能边界：无 cookie 时原画质/最高画质可能回退；`size_bytes=null` 时大小展示（下载探测）处理；抖音反爬升级导致的解析失败。

## 12. 账号与密钥

| 用途 | 配置项 | 申请方式 |
| --- | --- | --- |
| Release 签名库 | `RELEASE_KEYSTORE_BASE64` / `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD`（GitHub Secrets） | 自己 `keytool` 生成 `.jks`，base64 后配置（详见 README「发布正式包」） |
| 服务端 API Token/HMAC | `SERVER_API_TOKEN` / `SERVER_HMAC_KEY`（`local.properties` 与 `server/config.php` 一致） | 自行生成强随机串 |
| 抖音 Cookie（服务端优先模式） | `DOUYIN_COOKIE` / `DOUYIN_COOKIE_ENABLED=true`（`server/config.php`） | 浏览器登录抖音 F12 复制（等同账号凭据，勿公开） |

所有密钥均以占位符 `<YOUR_API_KEY>` 形式示例，真实值不回填源码。

## 13. 常见问题

| 现象 | 原因 / 解法 |
| --- | --- |
| `java: command not found` | JDK 未入 PATH，`export JAVA_HOME=/opt/java21`（本机）后重试 |
| 依赖下载超时/失败 | 设 `systemProp.dyparse.cnMirrors=true`（`~/.gradle/gradle.properties`）或 `DY_PARSE_CN_MIRRORS=true` |
| `assembleRelease` 失败 | 未配签名；临时可用 `assembleDebug`，正式发布按第 12 节配 Secrets |
| 解析返回 403 | 抖音风控：内置通道会自动 `AnonymousSessionStore` 兜底 + 预热 cookie 重试；可选部署 PHP 服务端并配置 Cookie 提升成功率 |
| 画质只显示默认/单档 | 无法拿到 `download_addr`/`bit_rate` 时走 ratio 多档兜底；个别作品可能只生成有限档位 |
| 图集实况保存后相册不识别 | 需静态图（`.jpg`）+ 同名 `.mov` 同目录配对（当前方案已实现），个别相册需手动刷新 |

## 14. 验收标准

1. 在 JDK 21 环境 `./gradlew assembleDebug` 成功产出 `app/build/outputs/apk/debug/app-debug.apk` 并可安装启动到首页。
2. 推送该源码到 GitHub 后，`.github/workflows/build.yml` 自动触发、构建成功并产出 `app-debug` artifact。
3. 推送 `v*` tag（如 `v4.10`）后 `release.yml` 在配置好签名 Secrets 时产出签名 release 并自动创建 GitHub Release（当前仓库未配 Secrets，由本地签名产物手动 `gh release create` 创建）。

---

*本交接包由资深 Android 交接专家生成；信息以代码与 Git 记录为准，标注 `[待补充]` 的项为环境缺失，不编造。*