# Android UI、协程与依赖注入

目标是保持页面范式、状态所有权和生命周期一致。不要把某个工具写成全局唯一答案。

## UI 选择

先读取同 feature 的 Activity/Fragment、布局、ViewModel 和测试，再按下列顺序判断：

1. 维护既有 XML/ViewBinding 页面时，延续邻近页面和真实基类，不为单个需求强迁 Compose。
2. 新建纯 Compose Activity 时，优先核验并采用项目的 `BaseComposeActivity` 生命周期约定，包括当前真实的 `onActivityCreated` 与 `ComposeContent` 扩展点。
3. `ComposeView` 只用于既有 XML/ViewBinding 页面中的渐进嵌入；设置与宿主生命周期匹配的 Composition 策略。
4. 任何 Compose 任务都必须再读 [UI-ARCHITECTURE](../../UI-ARCHITECTURE/SKILL.md)。若本页与专项规范冲突，以专项规范和当前源码为准。

主题、资源、无障碍、返回行为、窗口 inset 和状态恢复都应沿用相邻实现并测试，而不是从通用模板猜测。

## 状态与一次性事件

- ViewModel 暴露不可变 `StateFlow` 等可观察状态；UI 根据状态渲染。
- Toast、Snackbar、导航、分享、文件选择等平台副作用由 UI 层执行，不从 ViewModel 直接触发。
- 一次性事件可使用项目现有事件抽象；若邻近 feature 无抽象，可考虑 `Channel<Event>` 加 `receiveAsFlow()`，并明确缓冲、消费方和进程重建语义。
- 不把持久状态塞进一次性事件，也不把错误文本直接当异常内部信息透传。

## `execute {}` 与 `viewModelScope.launch`

两者都是合法工具，按任务语义选择。

### 使用 `execute {}`

当前 `BaseViewModel` 的 `execute {}` 是一次性任务封装，默认在 `viewModelScope` 中执行 IO，并把链式成功/失败/结束回调切回主线程。使用前重读真实实现，确认取消与异常行为未变化。

适合：

- 单次数据库、文件或网络 IO；
- 结果直接转为一次状态更新；
- 邻近 ViewModel 已一致使用该封装。

### 使用 `viewModelScope.launch`

以下场景可直接使用：

- 长期收集 `Flow`；
- 需要结构化并发或多个挂起步骤；
- 轮询、防抖、超时；
- 需要保存 `Job`、显式取消或重启；
- 主要是非阻塞挂起调用的生命周期流程。

阻塞或密集 IO 使用 `withContext(Dispatchers.IO)`，CPU 密集工作根据实际情况选择合适 dispatcher。不要为了形式在已经切换上下文的 API 外再套无意义 dispatcher。

### 取消与错误

- 禁止 `GlobalScope` 和脱离明确所有者的长期作用域。
- 捕获宽泛异常时必须先识别并重新抛出 `CancellationException`，保持取消可传播。
- 错误日志记录可诊断上下文但须脱敏；用户提示映射为稳定、可本地化文案。
- 不直接向 UI 展示 `localizedMessage`、堆栈、URL 中 token、SQL 或文件绝对路径。
- `onError`、`catch` 或空回调不得静默吞错；明确恢复、上报或状态转换。

## Hilt 局部采用

Hilt 不是全项目强制。先看邻近 feature：

- 已有 Hilt 时，保持 Android entry point、module/binding/provider、scope、ViewModel 和测试替换链完整。
- 邻近功能使用工厂或手动构造时，除非需求明确涉及 DI，不为单个修改迁移整个 feature。
- 不混用半套方案：仅加 `@HiltViewModel` 而没有可用创建入口，或仅加 `@AndroidEntryPoint` 而依赖无 binding，都不算完成。
- 插件和 version catalog 配置可能不一致；以有效构建配置为准，不声称版本已经统一。

## 列表与适配器

不要复制脱离仓库的 `ListAdapter`/DiffUtil 示例。先找相邻列表真实使用的 Adapter、数据更新、选择状态、payload、稳定 ID 和资源绑定方式；复用其可验证模式，仅在性能或正确性需要时改变。

## 验证重点

- 状态初值、加载/空/成功/失败和重试；
- 配置变化、返回栈、进程/页面重建及一次性事件不重复；
- 协程在页面或 ViewModel 结束时取消，Flow 不重复收集；
- Compose 重组、XML/Compose 嵌入生命周期、无障碍与交互；
- ViewModel 单元测试与必要的 Compose/UI instrumentation。

最终报告区分单元状态测试、Kotlin 编译、UI instrumentation 和真实设备人工验证。
