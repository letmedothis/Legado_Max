# Legado_Max（阅读 Max）项目设计说明

> 本文档由多智能体协同分析生成，综合了架构分析、功能分析、技术栈分析和 UI/UX 分析四个维度的深度研究。

---

## 一、项目概述

### 1.1 项目定位

**阅读 Max** 是一款基于 Legado（阅读 3.0）的 Android 电子书阅读器 fork，继承自 lyc 版，在其基础上新增大量实用和强大功能。核心差异化能力是**自定义书源规则引擎**——用户通过 Jsoup 选择器、XPath、JsonPath、正则表达式和 Rhino JavaScript 编写规则，从任意网页抓取和解析书籍内容。

### 1.2 版本策略

| 包名 | 说明 |
|------|------|
| `io.legado.app`（appLegacy） | 与原版 Legado 相同，可覆盖更新 |
| `io.legado.app.yuedu`（appMax） | 主开发目标，与原版共存 |
| `io.legado.app.yuedu.a`（appS） | 另一个共存包名 |

### 1.3 技术栈概览

- **语言**：Kotlin 2.3.10
- **构建**：AGP 9.2.1 + Gradle 9.4.1 + Version Catalog + KSP2
- **UI 框架**：Android View（XML + ViewBinding）为主 + Jetpack Compose（Material3）为辅
- **架构模式**：MVVM（ViewModel + LiveData/Flow）
- **数据库**：Room 2.8.4（version=100，30+ 实体，30+ DAO）
- **网络**：OkHttp 5.3.2 + Cronet（Chromium 网络栈）
- **JS 引擎**：Mozilla Rhino（自研 fork，用于书源规则执行）
- **事件总线**：LiveEventBus 1.8.14
- **异步**：Kotlin Coroutines 1.10.2 + Flow
- **前端**：Vue 3 + Vite（嵌入式 Web 管理界面）

---

## 二、架构设计方案

### 2.1 整体架构层次

```
┌─────────────────────────────────────────────────────────────────┐
│                     入口层 (Entry Point)                         │
│  App.kt → Application 初始化（崩溃处理/主题/数据库/JS引擎/服务）    │
│  WelcomeActivity → MainActivity（Fragment 导航宿主）              │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│                   UI 表现层 (Presentation)                       │
│  Activity/Fragment（XML + ViewBinding 为主）                      │
│  Compose 页面（theme/manage 等新功能）                            │
│  ViewModel ×85（BaseViewModel : AndroidViewModel）               │
│  RecyclerView Adapter                                            │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│                  业务逻辑层 (Business / model/)                   │
│  ReadBook（阅读核心状态管理）    WebBook（网络书源请求门面）         │
│  CacheBook（缓存管理）         AnalyzeRule（规则解析引擎）         │
│  LocalBook（本地书籍解析）      AudioPlay（音频播放控制）           │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│                     数据层 (Data / data/)                        │
│  Room Database (legado.db, version=100)                          │
│  30+ DAO 接口    30+ 实体类    Repository 层（增量演进中）          │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│                   基础设施层 (Infrastructure)                     │
│  service/（前台服务：朗读/下载/音频/Web）                          │
│  web/（NanoHTTPD + WebSocket 嵌入式服务器）                       │
│  help/（HTTP 帮助/配置管理/书源管理/备份）                         │
│  utils/（100+ Kotlin 扩展函数）                                   │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│                      横切关注点 (Cross-Cutting)                   │
│  LiveEventBus 事件总线    Coroutine 链式封装    日志/崩溃/统计     │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 Gradle 模块化

```
settings.gradle
├── :app              ← 全部业务代码（1200+ 文件，85 个 ViewModel）
├── :modules:book     ← epublib fork（EPUB/UMD/MOBI 解析）
└── :modules:rhino    ← Mozilla Rhino fork（JS 执行引擎）

modules/web/          ← 独立 Vue 3 + Vite 前端（非 Gradle 模块）
```

**模块间通信**：直接调用 API + 接口抽象 + 事件总线三种方式并存。

### 2.3 MVVM 架构实现

项目采用典型的 **MVVM（Model-View-ViewModel）** 架构，呈现"新老双轨"演进状态：

#### 老架构模式（主干）
```kotlin
// Activity/Fragment 中注入 ViewModel
override val viewModel by viewModels<SearchViewModel>()

// ViewModel 中通过 execute {} 执行 IO 并链式回调
fun addToBookshelf(book: SearchBook) {
    execute { appDb.bookDao.insert(book.toBook()) }
        .onError { AppLog.put("加入书架失败", it) }
}
```

- 状态载体：`MutableLiveData` + `ConflateLiveData`（延迟去重）+ `LiveEventBus` 事件
- 数据访问：ViewModel 直接操作全局 `appDb`

#### 新架构模式（Compose 页面）
```kotlin
// 严格 UDF 单向数据流
class ThemeManageViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(UiState())
    val uiState: _uiState.asStateFlow()  // 只读暴露

    private val _events = Channel<Event>(BUFFERED)
    val events = _events.receiveAsFlow()  // 一次性事件（防重放）
}
```

- 状态载体：`StateFlow` + `Channel`
- 数据访问：通过 `Repository` 封装

### 2.4 核心设计模式

| 模式 | 应用场景 | 示例 |
|------|---------|------|
| **模板方法** | Activity 基类生命周期 | `BaseActivity.onCreate` → `observeLiveBus()` |
| **策略模式** | 规则解析引擎 | `AnalyzeRule` 按前缀分派到 XPath/JSoup/JsonPath/正则/JS 五类解析器 |
| **单例模式** | 全局状态管理 | `object ReadBook / WebBook / CacheBook / AudioPlay` |
| **仓库模式** | 数据访问封装 | `BookRepository`、`ThemeRepository` |
| **工厂模式** | ViewModel 创建 | `ThemeManageViewModelFactory` |
| **观察者模式** | 响应式数据流 | LiveData / Flow / LiveEventBus |
| **门面模式** | 统一网络入口 | `WebBook` 统一书源请求 |
| **桥接模式** | Java/JS 互操作 | `RhinoWrapFactory` 实现 Java↔JS 对象桥接 |

### 2.5 数据库架构

**核心实体关系**：

```
Book ||--o{ BookChapter : 包含
Book ||--o| BookSource : 来源
Book ||--o| Bookmark : 书签
Book ||--o| ReadRecord : 阅读记录
BookSource ||--o{ Cookie : 使用
BookSource ||--o{ SearchBook : 产生
RssSource ||--o{ RssArticle : 包含
RssSource ||--o| RssReadRecord : 记录
```

**主要数据表**：Book（书籍）、BookChapter（章节）、BookSource（书源）、RssSource（RSS 源）、ReadRecord（阅读记录）、Bookmark（书签）、ReplaceRule（替换规则）、SearchKeyword（搜索关键词）等 30+ 张表。

### 2.6 事件总线机制

使用 `LiveEventBus` 实现跨组件解耦通信，支持生命周期感知、粘性事件、线程安全：

**主要事件分类**：
- **书架事件**：`UP_BOOKSHELF`、`BOOKSHELF_REFRESH`、`SOURCE_CHANGED`
- **阅读事件**：`UP_CONFIG`、`UPDATE_READ_ACTION_BAR`、`UP_SEEK_BAR`
- **朗读/TTS 事件**：`ALOUD_STATE`、`TTS_PROGRESS`、`READ_ALOUD_DS`
- **音频事件**：`AUDIO_DS`、`AUDIO_STATE`、`AUDIO_PROGRESS`
- **视频事件**：`VIDEO_SUB_TITLE`、`VIDEO_STATE`
- **通用事件**：`RECREATE`、`NOTIFY_MAIN`、`WEB_SERVICE`、`DEBUG_MODE_CHANGED`

### 2.7 网络请求架构

```
ViewModel → WebBook → AnalyzeUrl → [URL 模板变量替换/请求头构建/Cookie 处理]
                                        ↓
                               OkHttp / Cronet 双栈网络层
                                        ↓
                               StrResponse → 登录检测 JS → AnalyzeRule 解析
                                        ↓
                               规则匹配 → 数据提取 → 返回结果
```

**双栈网络**：
- **OkHttp 5.3.2**：标准 HTTP 请求，拦截器链横切（日志/重试/Cookie/认证）
- **Cronet 128**：Chromium 网络栈，运行期 MD5 校验懒加载 so 库

---

## 三、核心功能模块

### 3.1 书架管理

| 功能 | 说明 |
|------|------|
| 双布局模式 | 列表书架 / 网格书架自由切换 |
| 分组管理 | 书籍分组、拖拽排序、分组筛选 |
| 多通道导入 | 本地导入 / WebDAV / URL 协议 / 扫码 / 口令 / 智能导入 |
| 下拉刷新 | 带限流的书架刷新机制 |
| 双状态图标 | 新/经典两种书架图标样式 |
| 封面图集 | 多封面方案管理和切换 |
| HTML 封面模板 | 用户自定义 HTML 模板渲染书籍封面 |

### 3.2 书源系统

书源是 Legado 的核心概念，定义了如何从网站获取书籍信息。

**书源规则结构**：
```json
{
  "bookSourceUrl": "https://example.com",
  "bookSourceName": "示例书源",
  "searchUrl": "https://example.com/search?key={{key}}&page={{page}}",
  "exploreUrl": "分类1::https://example.com/cat1",
  "ruleSearch": { "bookList": "//div[@class='book-list']/div", ... },
  "ruleBookInfo": { ... },
  "ruleToc": { ... },
  "ruleContent": { ... }
}
```

**规则解析引擎**（`AnalyzeRule`）支持五种解析方式：
1. **XPath** — `AnalyzeByXPath`
2. **JSoup** — `AnalyzeByJSoup`
3. **JsonPath** — `AnalyzeByJSonPath`
4. **正则表达式** — `AnalyzeByRegex`
5. **Rhino JavaScript** — 自定义 JS 脚本执行

**书源操作流程**：
```
搜索(searchBook) → 发现(exploreBook) → 详情(getBookInfo) → 目录(getChapterList) → 正文(getContent)
```

**Max 特色增强**：
- `@webjs` 规则类型（在 WebView 中执行 JS）
- 代码编辑器增强（语法着色/规则切换/查询跳转）
- 书源检测新界面
- 规则回收站
- URL 变更自动迁移书架书籍
- 高亮规则作用域体系

### 3.3 阅读引擎

**阅读核心流程**：
```
打开书籍 → resetData → 判断本地/网络 → [LocalBook 解析 / WebBook 请求]
    → ContentProcessor 处理 → 替换规则应用 → ChapterProvider 排版
    → TextChapter 生成 → UI 渲染 → 翻页操作 → 保存进度
```

**核心特性**：
- **三章节滑动窗口缓存**：当前章 + 上一章 + 下一章预加载
- **四种翻页模式**：覆盖、仿真、滑动、滚动
- **本地格式支持**：TXT / EPUB / UMD / MOBI / PDF
- **净化替换**：去除广告、替换内容
- **目录渐进加载**：避免一次性加载所有目录
- **向前/向后预下载**：可调节的预下载章节数
- **朗读/TTS**：章节定时、系统媒体通道、多 TTS 源支持

**Max 特色增强**：
- 翻页速度自由调节
- 页眉页脚字体独立调节
- 波浪线 / 虚线 / SVG 下划线体系
- 选区放大镜、摘录分享

### 3.4 Web 服务

**三通道 API 架构**：

| 通道 | 端口 | 协议 | 用途 |
|------|------|------|------|
| HTTP Server | 1234 | NanoHTTPD | REST API（书架/书源/备份管理） |
| WebSocket Server | 1235 | WebSocket | 实时通信（阅读同步/状态推送） |
| ContentProvider | - | Content Provider | 应用间数据共享（ReaderProvider） |

**Vue 3 Web 前端功能**：
- 书架管理（查看/编辑/排序）
- 书源编辑器（可视化编辑/调试）
- 完全备份与恢复
- Web 端阅读界面

### 3.5 音视频播放

**音频播放**：
- TTS 听书（多 TTS 源、章节定时、系统媒体通道）
- 在线音频播放
- 悬浮窗控制

**视频播放**：
- m3u8 流媒体支持
- 静音 / 跳过片头片尾
- 悬浮窗播放
- 弹幕支持（DanmakuFlameMaster）

### 3.6 WebDAV 同步

- 备份恢复（完全备份含登录信息/运行变量/缓存书籍）
- 书籍上传下载
- 阅读进度同步
- 封面图集同步

---

## 四、Max 特色功能详解

### 4.1 调试与开发工具

| 工具 | 说明 |
|------|------|
| 集成调试工具台 | 编码转换 / Curl / Ping / 正则测试 / 时间戳 |
| 调试日志悬浮球 | 专属通道 / 实体 / 数据流转板块，实时查看调试日志 |
| TTS 源调试 | 专项调试 TTS 引擎 |
| 字典/字重调试 | 字体和字典功能的专用调试界面 |
| 书源检测新界面 | 可视化书源健康检查 |
| 代码编辑器增强 | 语法着色 / 规则切换 / 查询快速跳转 |

### 4.2 数据与记录管理

| 功能 | 说明 |
|------|------|
| 阅读记录四视图 | 列表 / 日历 / 统计 / 详情四种查看方式 |
| 热力图日历 | GitHub 风格的阅读热力图 |
| URL 记录 | 记录所有网络请求 URL，便于排查问题 |
| 完全备份 | 含登录信息 / 运行变量 / 缓存书籍的完整备份 |
| 备份文件验证 | 备份前验证文件完整性和格式正确性 |
| 阅读记录搜索 | 在阅读记录中搜索特定内容 |

### 4.3 UI/UX 优化

| 功能 | 说明 |
|------|------|
| HTML 封面模板引擎 | 用户自定义 HTML 模板渲染书籍封面 |
| 应用主题包 | 日/夜主题 + 顶栏 + 底栏 + 封面图集一键打包切换 |
| 顶栏管理 | 样式 / 壁纸 / 圆角 / 标签配色独立配置 |
| 底栏管理 | 布局模式 / 效果 / 自定义图标 |
| 段评气泡管理 | SVG 模板 / 日夜间配色 / 气泡样式 |
| 主题置顶 | 常用主题快速切换 |
| 主题批量操作 | 批量导入/导出/删除主题 |

### 4.4 功能扩展增强

| 功能 | 说明 |
|------|------|
| `@webjs` 规则 | 在 WebView 中执行 JavaScript 的新规则类型 |
| JS 增强函数集 | `java.showBrowser` 半屏段评等扩展 API |
| 智能导入 | 自动识别和导入书源/订阅源/替换规则 |
| 自动更新 | 书源和订阅源的自动更新检查 |
| JS Packages | JavaScript 包管理使用指南 |
| 直链上传 | 支持直链方式上传文件 |

---

## 五、UI/UX 设计体系

### 5.1 设计语言

- **Material Design 3**（Material You）为主要设计语言
- **日/夜间模式**完整支持，自动跟随系统或手动切换
- **主题系统**层次化管理：ThemeConfig（预置主题）→ ThemeStore（持久化）→ MaterialValueHelper（读取层）→ 全局应用

### 5.2 自定义组件

| 组件 | 说明 |
|------|------|
| `TitleBar` | 自定义标题栏，继承 AppBarLayout，支持亮/暗色、内容嵌入、状态栏适配 |
| `RecyclerViewAtPager2` | 适配 ViewPager2 的 RecyclerView |
| 自定义阅读 View | 阅读页面核心渲染组件 |
| 段评气泡 | SVG 模板驱动的段落评论气泡 |

### 5.3 主题系统架构

```
ThemeConfig（预置主题 JSON）
    ↓
ThemeStore（SharedPreferences 持久化）
    ↓
MaterialValueHelper（Kotlin 扩展属性读取）
    ↓
TitleBar / Theme 控件 / XML 属性引用 / 代码直接获取
```

### 5.4 Compose 新页面规范

新 Compose 页面遵循 `UI-ARCHITECTURE` 规范：
- 目录结构：`components/widget/theme/[feature]`
- 状态管理：UDF 单向数据流（StateFlow + Channel）
- 数据访问：通过 Repository 封装
- 跨层引用有明确红线

---

## 六、技术栈详细分析

### 6.1 依赖全景

| 类别 | 库 | 版本 |
|------|-----|------|
| **核心** | Kotlin + Coroutines | 2.3.10 / 1.10.2 |
| **AndroidX** | core-ktx, appcompat, activity-ktx, fragment-ktx | 最新 |
| **UI** | Material, ConstraintLayout, RecyclerView, ViewPager2 | Material3 |
| **数据库** | Room (runtime, ktx, compiler via KSP) | 2.8.4 |
| **网络** | OkHttp + Cronet | 5.3.2 / 128 |
| **图片** | Glide (okhttp 集成, svg 插件) | - |
| **规则解析** | Jsoup + JsonPath + JsoupXpath + Rhino | 自研 fork |
| **视频/音频** | GSYVideoPlayer + ExoPlayer + DanmakuFlameMaster | - |
| **Web 服务** | NanoHTTPD + NanoHTTPD-WebSocket | - |
| **Markdown** | Markwon (core, image-glide, tables, html) | - |
| **事件总线** | LiveEventBus | 1.8.14 |
| **其他** | ZXing-Lite, ColorPicker, libarchive, Sora Editor, LyricViewX | - |

### 6.2 构建配置

- **compileSdk / targetSdk**：36
- **minSdk**：21
- **JDK**：17
- **注解处理**：KSP2（替代 KAPT，承载 Room/Glide/Hilt）
- **依赖管理**：Version Catalog（`gradle/libs.versions.toml`）
- **混淆**：ProGuard + 资源收缩（release 构建）
- **CI/CD**：GitHub Actions（三 flavor 矩阵构建 + 自动发版）

### 6.3 代码规模

| 区域 | 规模 |
|------|------|
| XML 布局文件 | 672 个 |
| Compose 文件 | 146 个 |
| ViewModel | 85 个 |
| DAO | 30+ 个 |
| 实体类 | 30+ 个 |
| 工具扩展 | 100+ 文件 |

---

## 七、架构优缺点评估

### 7.1 优点

1. **分层清晰**：UI / model / data / service / help / web 六层职责分明
2. **事件总线解耦出色**：阅读、朗读、下载、Web 服务通过 LiveEventBus 协作，互不直接引用
3. **规则引擎抽象优秀**：策略分派 + Rhino 沙箱 + 三级缓存，支撑庞大书源生态
4. **协程封装实用**：`Coroutine` 链式 API 降低异步复杂度，Semaphore 限流、timeout 超时开箱即用
5. **响应式书架**：DAO 返回 Flow，全局状态自动跟随 DB 更新
6. **工程化完善**：三 flavor 共存、KSP、Room schema 导出、CI 全 flavor 构建

### 7.2 技术债务

| 优先级 | 问题 | 影响 |
|--------|------|------|
| **高** | 依赖注入名存实亡（Hilt 仅 3 处使用） | 核心逻辑无法单元测试 |
| **高** | `allowMainThreadQueries()` 已开启 | 潜在 ANR 风险 |
| **高** | 单一 `:app` 模块 1200+ 文件 | 增量编译慢、复用受限 |
| **中** | 数据层封装不一致（新 Repository 与老 appDb 直连并存） | 可测试性受限 |
| **中** | 状态管理双轨（LiveData 与 Flow/StateFlow 混用） | 认知成本高 |
| **中** | 巨型可变单例（ReadBook 暴露大量公开 var） | 并发与生命周期风险 |
| **低** | Firebase 全版本生效的隐私问题 | 合规风险 |
| **低** | configuration cache 未开启 | 构建性能损失 |

### 7.3 演进方向

项目正在有计划地推进架构现代化：
- `REFACTOR_PLAN.md` 规划迁移到 `@HiltViewModel + @Inject constructor`
- 新 Compose 页面已按 UDF + Repository 标准模式实现
- `UI-ARCHITECTURE` 规范约束新页面开发方式
- Repository 层逐步替代 ViewModel 直连 appDb

---

## 八、关键文件索引

| 类别 | 文件路径 |
|------|---------|
| **Application** | `app/src/main/java/io/legado/app/App.kt` |
| **基类** | `app/src/main/java/io/legado/app/base/BaseActivity.kt` |
| **ViewModel 基类** | `app/src/main/java/io/legado/app/base/BaseViewModel.kt` |
| **协程封装** | `app/src/main/java/io/legado/app/help/coroutine/Coroutine.kt` |
| **数据库** | `app/src/main/java/io/legado/app/data/AppDatabase.kt` |
| **阅读核心** | `app/src/main/java/io/legado/app/model/ReadBook.kt` |
| **网络书源** | `app/src/main/java/io/legado/app/model/webBook/WebBook.kt` |
| **规则引擎** | `app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeRule.kt` |
| **事件总线** | `app/src/main/java/io/legado/app/utils/EventBusExtensions.kt` |
| **事件常量** | `app/src/main/java/io/legado/app/constant/EventBus.kt` |
| **主题管理** | `app/src/main/java/io/legado/app/help/config/ThemeConfig.kt` |
| **Web 服务** | `app/src/main/java/io/legado/app/web/` |
| **架构文档** | `Structure/` 目录 |
| **新架构范本** | `app/src/main/java/io/legado/app/ui/config/theme/manage/ThemeManageViewModel.kt` |

---

## 九、总结

**阅读 Max** 是一个"业务能力极强、架构演进中"的大型单体 Android 应用。其核心竞争力在于：

1. **强大的书源规则引擎**——五种解析方式 + Rhino JS 沙箱，支撑用户自定义从任意网页抓取内容
2. **完善的阅读体验**——多种翻页模式、高度自定义界面、净化替换、朗读/TTS
3. **丰富的调试工具**——集成调试台、日志悬浮球、书源检测，形成开发闭环
4. **灵活的主题系统**——应用主题包、顶栏/底栏/气泡独立管理、HTML 封面模板
5. **多端协同**——嵌入式 Web 服务器 + Vue 前端实现远程管理

在架构层面，项目正从"老 MVVM + 事件驱动"向"UDF + Repository + 依赖注入"的现代架构演进，新 Compose 页面已采用标准模式，但大量老代码仍需逐步迁移。主要的技术债务集中在依赖注入落地、数据层封装统一、模块化拆分三个方面。

---

*文档生成时间：2025年8月17日*
*分析方法：多智能体协同（架构分析 + 功能分析 + 技术栈分析 + UI/UX 分析）*
