# Legado Dev Skill Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Repair every confirmed P1 and P2 issue in the `legado-dev` project skill so it is discoverable, factually aligned with the current checkout, progressively loaded, security-aware, and verifiable.

**Architecture:** Replace the 624-line monolith with a short routing `SKILL.md` and five focused reference documents. Keep `legado-dev` as the single skill name, route tasks to the minimum required references, and move its `CLAUDE.md` registration outside the generated Superpowers block. Validate structure mechanically, then use independent Room, Compose/coroutine, and Web/security scenario reviewers for behavioral verification.

**Tech Stack:** Markdown Agent Skills, YAML frontmatter, PowerShell, Python `quick_validate.py`, Gradle/Room/Compose/Vue project evidence, multi-agent scenario review.

---

### Task 1: Capture RED Baseline

**Files:**
- Read: `.claude/skills/legado-dev/SKILL.md`
- Create: `.claude/skills/legado-dev/evals/evals.json`

- [x] Run `quick_validate.py` against the original skill and confirm it fails with `No YAML frontmatter found`.
- [x] Dispatch independent Room, Compose/coroutine, and Web/security baseline reviewers.
- [x] Record the three prompts and objective assertions in `evals/evals.json`.

### Task 2: Create the Routing Skill

**Files:**
- Modify: `.claude/skills/legado-dev/SKILL.md`

- [x] Add YAML frontmatter with `name: legado-dev` and a trigger-only English description.
- [x] Reduce the entry point below 200 lines.
- [x] Add project-discovery, TDD/debugging, safety, task-routing, and completion gates.
- [x] Remove contradictory absolute bans and large copyable templates.

### Task 3: Add Focused References

**Files:**
- Create: `.claude/skills/legado-dev/references/project-map.md`
- Create: `.claude/skills/legado-dev/references/database-migrations.md`
- Create: `.claude/skills/legado-dev/references/android-ui-coroutines.md`
- Create: `.claude/skills/legado-dev/references/security-web.md`
- Create: `.claude/skills/legado-dev/references/verification.md`

- [x] Document the current project map without treating drift-prone versions as permanent truth.
- [x] Document the Room decision tree, real `appDb`, historical migration preservation, and instrumented `MigrationTest` boundary.
- [x] Document `BaseComposeActivity`, `UI-ARCHITECTURE`, `StateFlow`/event boundaries, and `execute` versus `viewModelScope.launch` selection.
- [x] Document APK secret boundaries, high-risk HTTP/WebSocket/upload/auth changes, and local `GITHUB_ENV` Web sync behavior.
- [x] Document risk-proportional verification and explicit incomplete-validation reporting.

### Task 4: Repair Skill Registration

**Files:**
- Modify: `CLAUDE.md`

- [x] Restore the generated Superpowers block to its prior count and remove the manually inserted `legado-dev` line from that block.
- [x] Add a project-specific Skill section after the generated block with a trigger-only registration statement.

### Task 5: Mechanical GREEN Verification

**Files:**
- Verify: `.claude/skills/legado-dev/**/*.md`
- Verify: `CLAUDE.md`

- [x] Run `quick_validate.py`; expect exit code 0 and `Skill is valid!`.
- [x] Assert the entry file is under 200 lines and all referenced files exist.
- [x] Assert forbidden misleading phrases are absent.
- [x] Assert required risk and workflow concepts are present.
- [x] Check Markdown links and Git diff for unintended changes.

### Task 6: Behavioral GREEN Verification and Review

**Files:**
- Read: `.claude/skills/legado-dev/**`
- Read: project evidence files cited by the skill

- [x] Rerun the same three scenario evaluations with the repaired skill.
- [x] Require an independent spec-compliance review for all P1/P2 findings.
- [x] Require a separate quality review for clarity, progressive disclosure, duplication, and maintainability.
- [x] Fix any confirmed issue and rerun the relevant review.
- [x] Report static/behavioral validation separately from Android build or runtime validation.
