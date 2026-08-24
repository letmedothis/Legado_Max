我为你整理了一份完整的 Markdown 文档，你可以直接保存到你的功能分支中。建议将文件放在仓库根目录下，例如 `SYNC_UPSTREAM_GUIDE.md`，方便以后随时查阅。

---

## 📄 文档内容

```markdown
# Fork 仓库同步上游并保留自定义修改操作指南

> 适用场景：你 fork 了一个 GitHub 项目，并在自己的 fork 中修改了代码，同时希望持续同步原项目的更新，且保留自己的修改。  
> 本文档记录了完整的操作流程、常见问题解决方法和日常维护命令。

---

## 一、目标

- 保持 `main` 分支与上游原项目完全一致（干净、可随时同步）
- 将自定义修改保存在独立的功能分支中（如 `my-changes`）
- 定期将上游更新合并到功能分支，保持基于最新代码

---

## 二、网络配置（重要）

由于网络限制，直连 GitHub 可能失败（HTTPS 握手错误、SSH 连接被关闭）。推荐使用 SSH over 443 端口，稳定且绕过防火墙。

### 1. 配置 SSH 走 443 端口

```bash
mkdir -p ~/.ssh
cat >> ~/.ssh/config << 'EOF'
Host github.com
    Hostname ssh.github.com
    Port 443
    User git
    IPQoS=throughput
EOF
```

### 2. 测试 SSH 连接

```bash
ssh -T git@github.com
```

应看到：`Hi 用户名! You've successfully authenticated...`  
如果失败，检查 SSH 密钥是否已添加到 GitHub（Settings → SSH and GPG keys）。

### 3. 设置远程仓库 URL 为 SSH 格式

```bash
# 你的 fork（origin）
git remote set-url origin git@github.com:你的用户名/项目名.git

# 上游原项目（upstream）
git remote set-url upstream git@github.com:原作者/项目名.git
```

---

## 三、初始同步与分支整理

### 1. 拉取上游最新代码

```bash
git fetch upstream
```

### 2. 确保当前修改已提交

```bash
git status
# 如果有未提交修改：
git add .
git commit -m "描述你的修改"
```

### 3. 创建功能分支保存你的修改

```bash
git checkout -b my-changes
git push origin my-changes
```

### 4. 将主分支重置为与上游完全一致

```bash
git checkout main
git reset --hard upstream/main
git push origin main --force
```

> ⚠️ `--force` 会覆盖远程 `main`，但你的修改已备份到 `my-changes`，所以安全。

### 5. 将功能分支 rebase 到最新 main

```bash
git checkout my-changes
git rebase main
```

如果出现冲突，参考下文“冲突解决”。

完成后强制推送：

```bash
git push origin my-changes --force
```

---

## 四、冲突解决

### 1. 查看冲突文件

```bash
git status
```

### 2. 快速选择某一方的版本

在 rebase 过程中：
- 保留上游最新版本（丢弃自己的修改）：`git checkout --ours <file>`
- 保留自己的修改（丢弃上游更新）：`git checkout --theirs <file>`

然后标记解决并继续：

```bash
git add <file>
git rebase --continue
```

### 3. 手动编辑冲突（推荐）

打开冲突文件，会看到类似标记：

```text
<<<<<<< HEAD
上游最新代码内容
=======
你自己的修改内容
>>>>>>> 提交hash
```

手动删除标记，保留需要的内容，然后：

```bash
git add <file>
git rebase --continue
```

### 4. 如果冲突太多，中止 rebase 改用 merge

```bash
git rebase --abort
git merge main
# 解决冲突（只有一个冲突点）后：
git add .
git commit -m "合并上游 main 更新"
git push origin my-changes
```

---

## 五、日常同步流程

定期执行以下步骤，保持功能分支与上游同步：

```bash
# 1. 拉取上游最新代码
git fetch upstream

# 2. 更新本地 main 与上游一致
git checkout main
git merge upstream/main   # 或 git rebase upstream/main
git push origin main

# 3. 更新功能分支（推荐 rebase）
git checkout my-changes
git rebase main           # 或 git merge main
git push origin my-changes --force   # rebase 后需要强制推送
```

---

## 六、注意事项

- **永远不要在 `main` 分支上直接修改代码**，所有自定义修改都在功能分支进行。
- 强制推送（`--force`）会覆盖远程分支历史，确保自己知道后果，且功能分支只有自己使用时才安全。
- 如果上游更新频繁，建议定期同步，避免冲突积累。
- 若需将修改贡献给原项目，可从功能分支发起 Pull Request。

---

## 七、常见问题

**Q: `git fetch upstream` 报 `gnutls_handshake() failed` 或 `Connection reset`**  
A: 网络问题，优先配置 SSH over 443（见第二章），或使用代理。

**Q: rebase 时冲突频繁，如何处理？**  
A: 如果冲突太多，可改用 `git merge`，只需解决一次冲突。

**Q: 如何恢复误操作？**  
A: 使用 `git reflog` 查看历史操作，可找回丢失的提交。

---

*最后更新：2026-08-24*
```

---

## 📌 接下来你可以这样做

1. **先解决当前的 rebase 冲突**（如果你还在冲突状态）：  
   - 如果想保留 rebase 结果，手动解决冲突后 `git add` + `git rebase --continue`  
   - 如果想改用 merge，执行 `git rebase --abort` 后重新 `git merge main`

2. **将上述文档保存到你的功能分支**：  
   ```bash
   # 假设你已完成 rebase 或 merge，当前在 my-changes 分支
   cat > SYNC_UPSTREAM_GUIDE.md << 'EOF'
   （粘贴上面的 markdown 内容）
   EOF

   git add SYNC_UPSTREAM_GUIDE.md
   git commit -m "docs: 添加上游同步操作指南"
   git push origin my-changes
```

这样，以后你或任何 AI 助手都可以通过阅读这个文档快速了解操作步骤。如果你需要调整内容或格式，请告诉我。