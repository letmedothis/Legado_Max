# 项目地图与事实源

本页给出调查起点，不把当前快照当成永久真相。每次实施前都要用仓库现状复核模块、变体、依赖、API 和目录。

## 模块边界

以根目录 `settings.gradle` 为准，当前 Gradle 模块是：

- `:app`
- `:modules:book`
- `:modules:rhino`

`modules/web` 不是 Gradle 子模块，而是独立的 Vue、TypeScript、Vite、pnpm 工程。它产出的网页可能被同步进 Android assets，但它有独立的依赖、测试、构建和发布边界。

不要根据目录名猜模块。模块变化时先重读 `settings.gradle`，必要时再核对 Gradle 的实际 task 列表。

## 当前架构轮廓

- Android UI 同时存在 XML/ViewBinding 与 Jetpack Compose。旧页面应优先延续邻近范式；新页面需根据实际入口和专项 UI 规范选择。
- 持久化使用 Room，注解处理使用 KSP。数据库版本、实体、迁移声明和导出 schema 必须从当前源码共同核验。
- 异步主要使用 Kotlin Coroutines。具体作用域和 dispatcher 取决于生命周期、任务持续时间及现有封装。
- Hilt 是局部采用，不是全项目统一前提。部分功能仍可能使用手动构造、工厂或既有依赖获取方式。
- 规则解析与执行跨 `app`、`modules:book`、`modules:rhino`；修改前必须追踪真实调用链和运行边界。

这些描述只用于定位。不得据此推断某个类一定继承某基类、某 feature 一定启用 Hilt，或某版本在所有配置中一致。

## 首要事实源

按任务选择读取：

| 事实 | 优先位置 |
|---|---|
| Gradle 模块 | `settings.gradle` |
| 插件、仓库和全局构建配置 | 根 `build.gradle`、`gradle.properties` |
| Android flavor、build type、依赖与打包 | `app/build.gradle` |
| 依赖别名与版本 | `gradle/libs.versions.toml` 及实际引用处 |
| 清单、组件、权限、deep link | `app/src/*/AndroidManifest.xml` |
| Room 数据库与全局实例 | `app/src/main/java/io/legado/app/data/AppDatabase.kt` |
| schema 历史 | `app/schemas/` |
| 迁移注册 | 当前 `DatabaseMigrations` 定义及 `AppDatabase` 配置 |
| 迁移测试 | `app/src/androidTest/` 中当前 `MigrationTest` |
| UI/状态/协程模式 | 同 feature 的生产代码和测试，其次才是基类 |
| Web 脚本 | `modules/web/package.json`、`modules/web/scripts/`、相关 CI |
| 内嵌 Web 产物 | `app/src/main/assets/web/vue/` 与构建后的 `modules/web/dist/` |

版本号和 flavor 名不在本技能中固化。执行命令前读取构建文件或 task 输出，防止文档漂移。

## 邻近代码优先

1. 找到目标入口及直接调用方。
2. 读取同目录或同 feature 中最近的类似实现。
3. 对比其测试、状态模型、错误处理、依赖获取和生命周期。
4. 若旧模式与新模式并存，选择与目标入口相容且仍被维护的方案，不跨 feature 顺手统一。

不要复制脱离仓库的通用模板。适配器、基类、DI module、Repository、事件类型和资源命名都必须来自真实实现。

## Hilt 采用边界

Hilt 仅在目标 feature 已有完整采用或此次任务明确需要时延续。使用前核验：

- Android entry point 是否存在且生命周期正确；
- module、binding/provider、scope 是否完整；
- ViewModel 或其他消费者的注入入口是否真实生效；
- 测试是否具备对应替换或构造方式。

根插件配置、version catalog 和 feature 依赖可能暂时不一致。不要声称版本统一，也不要为顺手统一而扩大任务；先确认有效 classpath 和目标模块配置。

## 高风险边界

下列区域允许在需求明确时修改，但必须扩大调查和验证：

- `base` 基类、全局 Application、全局事件和通用扩展；
- AppDatabase、实体、DAO、迁移与用户数据；
- 书源、规则引擎、Rhino、JavaScript 和远程内容解析；
- HttpServer、WebSocket、控制器、路由、上传、备份、鉴权和 CORS；
- 依赖、混淆、签名、发布构建与生成/同步资产。

修改前列出消费者和失败模式；修改后执行专项测试，并说明未覆盖的设备、数据、网络、变体或发布边界。
