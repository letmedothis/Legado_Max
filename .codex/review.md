# Legado_Max 代码检查工作流

对本仓库执行只读、证据驱动的代码检查。第一阶段不得修改源码、生成文件、提交、合并或推送。

## 准备

1. 完整阅读 `AGENTS.md`、`CLAUDE.md` 和 `docs/project-rules/README.md`，按检查领域读取对应规范。
2. 执行 `git status --short --branch`、`git branch --show-current`、`git log --oneline --decorate -30`。
3. 当前分支应为 `my-changes`。优先审查 `my-changes` 相对 `main` 的变化；确认引用存在后使用 `git diff --merge-base main my-changes`。不得为了获得远端引用擅自改写历史。
4. 先定位模块接口、构建配置和同类实现，再进入实现细节。源码与文档不一致时以源码为准，并指出文档风险。

## 检查范围

只报告具有真实调用路径、可构造触发条件和用户可观察影响的问题：

- 编译、启动、运行错误，Crash、ANR、死锁、死循环和数据损坏。
- 生命周期错误，主线程 IO，协程取消/作用域/Flow/Channel/`BaseViewModel.execute` 时序问题。
- 可观察的性能回归、重复请求/查询/解析、Bitmap 或大文件内存峰值。
- Activity/View/Callback/Stream/ResponseBody/Socket/WebSocket/Executor/线程等资源泄漏。
- Room migration、事务、并发写、nullable/返回值假设、主键覆盖、N+1 和索引问题。
- OkHttp、Cronet、WebBook、NanoHTTPD/WebSocket 的关闭、取消、超时、重试、Cookie、编码和安全边界。
- `modules/rhino` 与 `model/analyzeRule` 的 Context enter/exit、ThreadLocal、异常、类型转换、超时和恶意规则阻塞。
- 阅读核心流程中的章节/页码/缓存/进度/书签/高亮状态竞争。
- Android API 23～37 兼容性、权限、存储、PendingIntent、前台服务、edge-to-edge、16KB page size 和 native 库。
- Release/R8/ProGuard、反射、序列化、Room、Rhino、JNI/Cronet 的仅 Release 风险。
- `my-changes` 相对 `main` 的调用方破坏、行为回归和无文本冲突的语义差异。

重点查看 `ReadBook`、`CacheBook`、`AudioPlay`、`help/coroutine`、Repository/DAO、WebBook、WebSocket、Rhino 和最近修改文件。不要用关键词命中代替调用链分析。

## 输出要求

按 P0、P1、P2 和“疑似问题，需要验证”分组。每项必须包含：

- 文件与函数/行号
- 触发条件和调用路径
- 实际影响及问题成立的证据
- 最小修复建议、影响范围和置信度

逐项反证：是否被已有保护覆盖、是否为平台允许行为、是否存在真实入口、能否构造场景。不能证明则降为疑似，不得用风格、命名、注释或理论微优化凑数量。

结尾汇总扫描范围、重点模块、各等级数量、验证限制和最值得优先修复的问题。等待用户明确要求后再进入 `.codex/fix.md`。
