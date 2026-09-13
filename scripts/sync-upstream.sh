#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  cat <<'EOF'
Usage: scripts/sync-upstream.sh [--release] [--web]

Safely merge upstream/main into a clean my-changes worktree, then run the
project's appMax debug build, unit tests, and lint. Release and web checks are
automatically enabled for relevant changed paths and can be forced by flags.
EOF
}

force_release=0
force_web=0

while (($# > 0)); do
  case "$1" in
    --release) force_release=1 ;;
    --web) force_web=1 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

repo_root=$(git rev-parse --show-toplevel 2>/dev/null) || {
  echo "Error: run this script inside the Legado_Max Git repository." >&2
  exit 1
}
cd "$repo_root"

current_branch=$(git branch --show-current)
if [[ "$current_branch" != "my-changes" ]]; then
  echo "Error: expected branch my-changes, found ${current_branch:-detached HEAD}." >&2
  exit 1
fi

if [[ -n "$(git status --porcelain=v1 --untracked-files=normal)" ]]; then
  echo "Error: the worktree or index is not clean. Commit or otherwise handle these files first:" >&2
  git status --short >&2
  exit 1
fi

if ! git remote get-url upstream >/dev/null 2>&1; then
  echo "Error: remote 'upstream' is not configured." >&2
  echo "Configure it explicitly, then rerun this script." >&2
  exit 1
fi

echo "Fetching upstream/main..."
git fetch upstream main

if ! git show-ref --verify --quiet refs/remotes/upstream/main; then
  echo "Error: upstream/main was not created by fetch." >&2
  exit 1
fi

before_merge=$(git rev-parse HEAD)
echo "Upstream commits not yet in my-changes:"
git log --oneline HEAD..upstream/main || true
echo "Three-dot change summary:"
git diff --stat HEAD...upstream/main || true

echo "Merging upstream/main into my-changes..."
set +e
git merge --no-edit upstream/main
merge_status=$?
set -e

if ((merge_status != 0)); then
  if [[ -n "$(git diff --name-only --diff-filter=U)" ]]; then
    echo "Merge conflicts detected. The merge state has been preserved for Codex-guided resolution:" >&2
    git diff --name-only --diff-filter=U >&2
    echo "Follow .codex/sync-upstream.md; do not rebase, reset, clean, or choose ours/theirs blindly." >&2
  else
    echo "Merge failed without unresolved paths. Inspect the Git output and status." >&2
  fi
  exit "$merge_status"
fi

changed_files=$(git diff --name-only "$before_merge"..HEAD)
run_release=$force_release
run_web=$force_web

if grep -Eq '(^|/)(proguard[^/]*|.*\.pro)$|(^|/)(build\.gradle(\.kts)?|settings\.gradle(\.kts)?|gradle\.properties|libs\.versions\.toml)$|(^|/)(jni|jniLibs|cpp|native)(/|$)|cronet' <<<"$changed_files"; then
  run_release=1
fi
if grep -Eq '^modules/web/' <<<"$changed_files"; then
  run_web=1
fi

echo "Running required Android verification..."
bash ./gradlew assembleAppMaxDebug
bash ./gradlew test
bash ./gradlew lint

if ((run_release == 1)); then
  echo "Release-sensitive paths changed; running appMax release build..."
  bash ./gradlew assembleAppMaxRelease
fi

if ((run_web == 1)); then
  if ! command -v pnpm >/dev/null 2>&1; then
    echo "Error: modules/web changed but pnpm is not available (requires Node >= 20 and pnpm >= 9)." >&2
    exit 1
  fi
  echo "Web paths changed; installing and building modules/web..."
  (
    cd modules/web
    pnpm install --frozen-lockfile
    pnpm build
  )
fi

git diff --check
git status --short --branch
echo "Upstream merge and configured verification completed. Review the result before any push."
