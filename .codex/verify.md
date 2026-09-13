# Legado_Max 验证工作流

在仓库根目录执行与改动风险相称的验证，使用项目 Gradle wrapper 和 JDK 17。开始前阅读 `AGENTS.md`、`CLAUDE.md`、`docs/project-rules/build-commands.md` 与 `docs/project-rules/ci-cd.md`。

## 基础门禁

按顺序运行：

```bash
bash ./gradlew assembleAppMaxDebug
bash ./gradlew test
bash ./gradlew lint
git diff --check
git status --short --branch
```

仓库中的 wrapper 未设置可执行位，因此统一通过 `bash ./gradlew` 调用。首次构建若缺 Cronet 原生库，按项目说明先运行 `bash ./gradlew app:downloadCronet`。格式相关改动可追加 `bash ./gradlew spotlessCheck`；不要未经审阅自动格式化无关文件。

## 条件验证

- Release/R8/ProGuard、反射、序列化、Room、Rhino、JNI/Cronet、native 或构建配置变化：`bash ./gradlew assembleAppMaxRelease`。
- `modules/web/` 变化：在该目录使用 Node >= 20、pnpm >= 9，运行锁文件对应的安装、`pnpm build`，并运行项目实际提供的 lint/format 检查脚本；先从 `package.json` 确认准确命令。
- 单一 Android lint 快速复核可运行 `bash ./gradlew :app:lintAppMaxDebug`；涉及特定模块或 flavor 时追加其定向测试/构建。

不得因时间或环境失败而宣称通过。记录每条命令的退出状态；区分代码失败、已有基线失败、工具链/网络限制和未运行项。

最后分别输出 Standards 与 Spec 两份 diff 审查结果，并汇总通过、失败、跳过的验证及剩余人工检查。不得自动提交或 push。
