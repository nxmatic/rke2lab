#!/usr/bin/env bash
# Shared guard: wip/ must never exist on the protected branch (main).
#
# wip/ holds living process artifacts (brainstorm → spec → plan) DURING a feature branch;
# its durable substance migrates to docs/architecture/ before merge. It must not land on main.
# We learned this the hard way: a fast-forward merge silently carried wip/ onto main.
#
# Sourced by both pre-commit (catches commits/squash-merges) and pre-push (catch-all, including
# fast-forward merges that create no commit). Defines guard helpers; the caller decides which.

PROTECTED_BRANCH="main"
WIP_DIR="wip"

# True when the given tree-ish has any tracked file under wip/.
wip_present_in() {
  local treeish="$1"
  test -n "$(git ls-tree -r --name-only "$treeish" -- "$WIP_DIR/" 2>/dev/null)"
}

# True when the working index (staged) has any tracked file under wip/.
wip_present_in_index() {
  test -n "$(git ls-files -- "$WIP_DIR/" 2>/dev/null)"
}

reject() {
  echo "─────────────────────────────────────────────────────────────" >&2
  echo "  BLOCKED: ${WIP_DIR}/ must not exist on '${PROTECTED_BRANCH}'." >&2
  echo "" >&2
  echo "  ${WIP_DIR}/ is for in-progress process artifacts on feature" >&2
  echo "  branches. Migrate durable substance to docs/architecture/ and" >&2
  echo "  delete ${WIP_DIR}/ before it reaches ${PROTECTED_BRANCH}." >&2
  echo "" >&2
  echo "  $1" >&2
  echo "─────────────────────────────────────────────────────────────" >&2
}
