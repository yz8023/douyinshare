# Requirements Document

## Introduction

在现有抖音（Douyin）解析器基础上，将 dyparse 扩展为**多平台短视频/图集解析工具**：用户粘贴任意受支持平台的分享链接，App 即可解析出无水印直链、图集、作者信息。解析能力**全部内置本地**（沿用内置服务器 + 本地解析通道），不强制依赖自建服务端；同时保留外部 PHP 服务端作为可选增强。

参考平台清单（源自 jiuhunwl/short_videos 项目）：抖音（已支持）、快手、小红书、bilibili、微博、今日头条、皮皮虾、皮皮搞笑、汽水音乐、网易云音乐。

## Glossary

- **System（系统）**：dyparse Android 客户端，含内置解析服务器（BuiltInServer）、内置解析引擎（BuiltInParser）、本地 WebView 解析引擎（LocalParseEngine）、剪贴板监听服务。
- **平台（Platform）**：一个独立的在线内容服务（如快手、小红书、bilibili）。每个平台有其独立的分享链接域名与 HTML/API 结构。
- **分享链接（Share Link）**：用户从某平台 App 复制的作品链接/分享文案。
- **作品（Work）**：平台上的单条内容，类型为视频（video）、图集（image）、直播实况（live）或音乐（music）。
- **解析结果（ParseResult）**：App 统一的作品数据模型（作者/标题/类型/直链/图集/画质）。
- **内置服务器**：App 进程内监听 127.0.0.1 的 HTTP 服务，模拟 data.php 端点，实现「安装即用」。

## Requirements

### REQ-1 平台路由识别

**User Story:** AS 用户, I want 粘贴任意平台分享链接即可自动识别平台，SO THAT 无需手动选择。

#### Acceptance Criteria

1. WHEN 用户粘贴一个受支持平台的分享链接（含域名或分享文案）, the system SHALL 依据链接域名识别目标平台。
2. WHEN 系统识别出目标平台, the system SHALL 将解析任务派发给该平台的解析器。
3. WHEN 输入串既不包含任何受支持平台域名也不能匹配任何平台 ID 规则, the system SHALL 返回「无法识别平台/不支持该平台」错误提示。
4. WHEN 输入串包含多个平台链接, the system SHALL 选择首个可识别平台的链接进行解析。

### REQ-2 各平台作品解析

**User Story:** AS 用户, I want 对每个受支持平台都能解析出作品信息与下载直链，SO THAT 下载作品不受水印与平台限制。

#### Acceptance Criteria

1. WHEN 用户输入一个快手分享链接, the system SHALL 返回快手作品的标题/作者/无水印视频直链（或图集图片列表）。
2. WHEN 用户输入一个小红书分享链接, the system SHALL 返回小红书视频/图文图集的无水印下载地址。
3. WHEN 用户输入一个 bilibili 分享链接（含 BV 号或 av 号）, the system SHALL 返回视频标题/UP主/最高可用画质播放地址。
4. WHEN 用户输入一个微博分享链接, the system SHALL 返回微博视频直链与作者信息。
5. WHEN 用户输入一个今日头条分享链接, the system SHALL 返回头条视频直链。
6. WHEN 用户输入一个皮皮虾/皮皮搞笑分享链接, the system SHALL 返回对应平台作品直链。
7. WHEN 用户输入一个汽水音乐/网易云音乐分享链接, the system SHALL 返回歌曲名/歌手/可下载音频直链。
8. WHEN 目标平台解析失败（链接失效/风控/超时）, the system SHALL 返回明确的失败原因，且不影响向后回退：内置服务器失败时回退本地 WebView 通道，单平台失败不影响其他平台。

### REQ-3 图集与实况支持

**User Story:** AS 用户, I want 图集作品能展示全部图片并整本下载，实况类作品能像抖音一样配对保存，SO THAT 多图作品无需逐张复制。

#### Acceptance Criteria

1. WHEN 目标平台作品是图集, the system SHALL 返回全部图片 URL 列表（images + gallery_media 结构），结果区展示多图预览。
2. WHEN 图集含实况照片（如快手/抖音实况）, the system SHALL 返回带 live_photo 媒体项，保存时按「静态图+同名视频」配对写入相册。
3. WHEN 用户在批量解析结果页点击图集「整本下载」, the system SHALL 下载该图集全部图片（含实况配对）。

### REQ-4 内置本地服务器多平台支持

**User Story:** AS 用户, I want 不配置任何外部服务器也能在各平台间切换使用，SO THAT 开箱即用。

#### Acceptance Criteria

1. WHEN 内置服务器处于运行状态, the system SHALL 在 /data.php 端点接受任何受支持平台的 url 参数并返回对应平台解析结果。
2. WHEN 内置服务器收到不支持的平台链接, the system SHALL 返回 success=false 与明确错误信息。
3. WHEN 外部 PHP 服务端被用户配置, the system SHALL 仍优先走外部服务端（其多平台能力由服务端版本决定），内置服务器作为缺省通道。

### REQ-5 剪贴板自动识别多平台

**User Story:** AS 用户, I want 复制任何平台链接后自动弹出解析，SO THAT 无需手动粘贴。

#### Acceptance Criteria

1. WHEN 剪贴板监听开启且新内容含任一受支持平台域名或合法 ID, the system SHALL 触发自动解析。
2. WHEN 新内容不含任何受支持平台特征, the system SHALL 忽略该次复制（保持现有行为）。

### REQ-6 结果展示与画质选择

**User Story:** AS 用户, I want 结果页继续展示画质+大小按钮并可切换，SO THAT 选择最合适的清晰度下载。

#### Acceptance Criteria

1. WHEN 解析结果为视频且解析器提供了多档画质, the system SHALL 在结果区展示「原画质/最高画质/分辨率」按钮并支持任意选择下载。
2. WHEN 平台未提供画质分组, the system SHALL 沿用现有 ratio 多档兜底（构造平台播放 URL 枚举档位）。
3. WHEN 解析结果为图集, the system SHALL 结果区展示图集预览与「整本下载」入口。
4. WHEN 解析结果为音乐（汽水音乐/网易云）, the system SHALL 展示歌曲/歌手信息与音频下载入口。

### REQ-7 保存兼容性

**User Story:** AS 用户, I want 下载的作品能正确存入相册，SO THAT 文件按平台/类型归类且可播放。

#### Acceptance Criteria

1. WHEN 保存视频, the system SHALL 使用对应平台的 UA/Referer 头发起下载，避免防盗链 403。
2. WHEN 保存视频到相册, the system SHALL 命名含平台标识（如 kuaishou_xxx.mp4），按平台放入子目录。
3. WHEN 保存图集, the system SHALL 沿用现有 jpg 命名与前缀序号规则。

### REQ-8 既有功能不回归

**User Story:** AS 现有用户, I want 新增多平台能力后抖音原有功能照常可用, SO THAT 升级无风险。

#### Acceptance Criteria

1. WHEN 用户仅使用抖音链接, the system SHALL 保持现有抖音解析链路（内置服务器/本地/外部）与画质/图集/实况/批量/历史等全部功能行为不变。
2. WHEN 作者批量解析输入抖音主页链接, the system SHALL 仍进入批量解析页（多平台作者的批量主页解析列为后续增强，不在本需求范围）。
3. WHEN 构建项目, the system SHALL 通过 assembleDebug 且全部现有单测通过。