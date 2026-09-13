# AGENTS.md

本文件适用于仓库根目录及全部子目录。

## 开始任务前

1. 完整阅读 [CLAUDE.md](./CLAUDE.md)，遵守其中的“核心规则”“Skill 的使用”和“AI 探索项目的方式”。
2. 阅读 [docs/project-rules/README.md](./docs/project-rules/README.md)，再按改动领域读取其中标明的必读规范。
3. 以源码为事实来源；文档与实现冲突时，以源码为准并同步修正文档或标注其已过时。
4. 先确认当前分支和工作区状态，保留用户已有改动，不修改与任务无关的文件。

## 实施与验证

- 功能改动遵循设计先行、测试先行；声称完成前必须运行与改动风险相称的构建、测试和 lint。
- 复用项目既有 JDK 17、Gradle wrapper、`appMax` 主 flavor、minSdk 23、compileSdk/targetSdk 37，以及现有 git hooks 和 CI 规则。
- 修改协程、Room/Repository、高版本 API、Compose、事件总线等代码前，必须读取 `docs/project-rules/` 下对应规范。
- 代码修改完成后，按 `CLAUDE.md` 要求从 Standards 和 Spec 两个维度审查 diff，并报告未执行或失败的验证。
- 不通过删除测试、降低检查级别、吞掉异常或添加无依据的抑制来绕过失败。

## Git 安全红线

AI 不得自动执行以下操作：

- `git push`（包括 force push 和直接推送 `main`/`master`）
- `git rebase`
- `git reset --hard`
- `git clean -fd`（以及其他 `git clean` 变体）
- `git commit --amend`
- 会覆盖未提交修改的 `git checkout -- <file>` 或 `git restore <file>`

确需执行上述操作时，必须先说明目标、影响与风险，并取得用户明确授权。不得用 `stash`、重置或清理工作区来绕过同步前检查。

允许在用户要求的上游同步任务中执行普通的 `git fetch upstream` 和 `git merge upstream/main`；遇到冲突必须逐文件理解 BASE/OURS/THEIRS，禁止无分析地整文件选择 `--ours` 或 `--theirs`。合并完成后还必须检查没有文本冲突的语义回归。

## 固定工作流入口

- 只读代码检查：`.codex/review.md`
- 分批修复问题：`.codex/fix.md`
- 同步上游：`.codex/sync-upstream.md`
- 构建与质量验证：`.codex/verify.md`

这些文件是提示词模板，不替代 `CLAUDE.md`、项目规范、Git、Gradle、测试或 CI。
