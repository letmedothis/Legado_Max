# Legado_Max 上游同步工作流

目标：把 `upstream/main` 安全合并到本地 `my-changes`，保留上游修复和本分支定制，并完成文本冲突、语义冲突及构建验证。允许普通 fetch/merge；禁止 push、rebase、reset、clean、amend 和自动 stash。

## 合并前

1. 完整阅读 `AGENTS.md`、`CLAUDE.md`、`docs/project-rules/README.md`，冲突处理遵守项目的 resolving-merge-conflicts 流程（若当前环境提供该技能则必须加载）。
2. 执行 `git status --short --branch`、`git branch --show-current`、`git remote -v`。
3. 必须处于 `my-changes`，工作区及 index 必须干净，且存在 `upstream` remote；任一条件不满足立即停止，不 stash、不覆盖修改。
4. 执行 `git fetch upstream main`，查看 `git log --oneline HEAD..upstream/main`、`git diff --stat HEAD...upstream/main` 和双方修改过的文件。
5. 合并前总结上游提交、涉及模块、双方重叠文件、潜在文本冲突与语义冲突。

也可运行 `scripts/sync-upstream.sh` 完成安全检查、fetch、merge 与验证；使用 `--release`/`--web` 可强制追加相应验证。

## 合并与冲突

执行 `git merge --no-edit upstream/main`。若冲突发生，保留合并状态并停止自动流程，逐文件分析：

- BASE：共同祖先；OURS：`my-changes`；THEIRS：`upstream/main`。
- 查阅双方提交意图和调用方，保留上游 API/数据结构、安全、Bug 修复和性能变化，再重新适配本分支功能。
- 不把上游删除的旧实现带回，不重复实现逻辑；禁止未经分析整文件使用 `--ours`/`--theirs`。

解决完全部冲突后执行 `git diff --check`、检查未合并文件并完成 merge commit。若需要创建提交，先向用户说明；不得 amend。

## 语义检查与验证

即使 Git 没有报告冲突，也要检查方法签名、nullable、类移动、Room schema/migration、Gradle/依赖、Compose、协程、WebBook、analyzeRule、ReadBook、CacheBook、AudioPlay、WebSocket 和 Rhino。

按 `.codex/verify.md` 运行基础验证。涉及 R8/ProGuard/native/Gradle 发布配置时追加 `assembleAppMaxRelease`；涉及 `modules/web` 时追加 Web 验证。

最终报告上游提交、自动合并与冲突文件、每项解决意图、语义适配、构建/test/lint 结果和人工验证项，然后停下等待用户决定是否 push。
