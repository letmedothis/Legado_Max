# 验证与交付

验证必须与风险相称，并来自当前执行证据。先用最小测试建立快速反馈，再扩大到目标编译、构建、instrumentation 或运行时。

## TDD 闭环

对功能和 Bug 的可测试行为执行：

1. **RED**：写最小测试，运行并确认它因目标行为缺失而失败，而非环境或语法错误。
2. **GREEN**：写最少实现，重新运行并确认该测试通过。
3. **REFACTOR**：整理实现，持续运行目标测试和相关回归。

纯文档、只改注释或不可合理自动化的变更可以不走行为 TDD，但仍需结构、链接、事实和差异验证，并在报告中说明原因。

## 风险矩阵

| 变更类型 | 最小证据 | 需要扩大时 |
|---|---|---|
| Skill/文档/配置说明 | frontmatter/格式验证、链接存在、关键事实对照源码、最终 diff | 示例场景评审、专用校验器 |
| Kotlin 纯逻辑 | 目标单元测试 RED→GREEN、目标 Kotlin 编译 | 相关模块测试、lint、variant 构建 |
| XML/ViewBinding/Compose | 状态/ViewModel 测试、目标编译 | Compose/UI instrumentation、截图或真实设备交互与无障碍 |
| Room/DAO/schema | DAO/逻辑测试、Room 编译、schema diff | 设备/模拟器上的 MigrationTest、完整升级链和真实 DAO 读写 |
| HttpServer/WebSocket/安全 | 认证与输入负例测试、目标编译 | 集成/运行时端点测试、真实网络边界、并发和日志检查 |
| `modules/web` | 类型检查、Vite 生产构建、`dist/` 检查 | 显式 assets 同步 diff、Android variant 构建、内嵌页面运行时 |
| 依赖/Gradle/混淆/发布 | task/依赖解析、受影响目标构建 | 所有受影响 variant、lint、release/R8、安装或烟测 |

数据库与安全的详细门槛分别见[数据库与迁移](database-migrations.md)和[安全与嵌入式 Web](security-web.md)。

## 命令选择

始终先从当前 wrapper、`settings.gradle`、模块构建文件和 `tasks --all` 核验 task 与 variant。不要无条件宣称所有 flavor 或 release 已覆盖。

Windows 常用候选：

```powershell
.\gradlew.bat tasks --all
.\gradlew.bat :app:testAppMaxDebugUnitTest
.\gradlew.bat :app:compileAppMaxDebugKotlin
.\gradlew.bat :app:assembleAppMaxDebug
.\gradlew.bat :app:lintAppMaxDebug
```

macOS/Linux 常用候选：

```bash
./gradlew tasks --all
./gradlew :app:testAppMaxDebugUnitTest
./gradlew :app:compileAppMaxDebugKotlin
./gradlew :app:assembleAppMaxDebug
./gradlew :app:lintAppMaxDebug
```

这些是候选而非承诺存在的命令。若 task 名不同，使用当前仓库实际等价 task，并报告选择依据。不要为了“全绿”默认执行所有昂贵 variant；受影响范围、风险或用户验收要求扩大时再扩大。

## Web 验证

在 `modules/web` 中按 lockfile 和 `packageManager`/engine 约束核验 Corepack、Node 与 pnpm。常用流程：

```bash
corepack enable
pnpm install
pnpm build
```

网络受限或依赖已存在时可选择更窄命令，但必须记录。验证内容包括：

- 类型检查和 Vite 构建均成功；
- `modules/web/dist/` 是本次生成且内容完整；
- 本地 `pnpm build` 是否实际同步 assets，不能根据退出码猜测；
- 需要嵌入发布时，显式同步并审查 `app/src/main/assets/web/vue/` 差异；
- 必要时再构建 Android variant 并做内嵌页面运行时验证。

## 最终差异检查

1. 查看状态和最终 diff，确认只包含授权范围。
2. 检查秘密、绝对本机路径、调试开关、临时文件和无关格式化。
3. 对生成文件核对来源、同步方式和删除项，避免陈旧产物混合。
4. 不覆盖或回退用户已有修改。
5. 未经明确授权，不 stage、commit、push、发布或创建 PR。

## 完成报告格式

逐项列出：

- **改了什么**：文件/符号及对应需求。
- **运行了什么**：实际命令和退出结果。
- **证明了什么**：单元行为、编译、构建、instrumentation 或运行时中的哪一层。
- **没运行什么**：原因、需要的环境/权限和剩余风险。
- **差异状态**：是否存在用户原有改动、生成资产和未跟踪文件。

必须分开使用以下表述：静态检查通过、目标编译通过、目标构建通过、instrumentation 通过、真实设备/运行时验证通过。前一层不能替代后一层；零测试源或未发现测试也不能称为业务测试通过。
