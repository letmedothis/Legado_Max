# Legado_Max 问题修复工作流

依据用户确认的检查结果分批修复；默认只处理 P0 和高置信度 P1，不顺手重构或处理无关问题。

## 约束

1. 完整阅读 `AGENTS.md`、`CLAUDE.md`、`docs/project-rules/README.md` 及涉及领域的规范。
2. 开始前确认 `my-changes` 和工作区状态，保留既有改动。先为问题建立可复现证据或测试，再做最小实现。
3. 不随意改变公共 API、数据库 schema、依赖或产品行为；确有必要时先说明原因、兼容策略和迁移风险。
4. 每个问题先说明根因、方案和影响范围，再修改。无法独立证实的问题先验证，不凭推测修改。
5. 不执行 push、rebase、reset、clean、amend，不用 stash 隐藏工作区状态。

修改协程前读 `coroutine-rules.md`；修改 Room/Repository 前读 `repository-rules.md`；涉及 API 23～37 或依赖前读 `api-compat-rules.md`；Compose 和事件总线按索引读取相应规则。

## 验证与复核

按 `.codex/verify.md` 执行适配改动的验证。失败时定位真实原因，不删除测试、不降低门禁、不用抑制规避。

完成后审查完整 diff：

- Standards：是否遵守项目规范、现有架构和相邻代码风格。
- Spec：是否只修复用户确认的问题，触发场景是否闭环，是否引入 Null、线程、生命周期、API 23、Release/R8、重复请求或数据兼容回归。

最终列出已修问题、修改文件、测试结果、未执行验证及原因、仍存问题和建议人工验证项。不要自动提交或推送。
