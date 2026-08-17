---
name: claude-auto-memory-mechanics
description: "How Claude Code resolves auto-memory + config dirs. Auto-memory is REPO-WIDE by default (one dir per git repo, shared across all worktrees, keyed off the git repository). autoMemoryDirectory setting (absolute or ~/ path, honored from settings.local.json under workspace-trust): VERIFIED on darwin 2026-08-14 — redirects BOTH read and write; it is THE mechanism to pin memory into a tracked per-worktree dir, and it REPLACED the old link-memory.sh symlink hack (now deleted). Only MEMORY.md auto-loads at start (first 200 lines / 25KB). CLAUDE_CONFIG_DIR is not documented to affect memory."
metadata:
  node_type: memory
  type: reference
---

Hard facts about Claude Code's memory/config resolution, learned the hard way on
2026-06-15 (darwin, VSCode extension, Bedrock auth). Verified against
code.claude.com/docs unless marked.

**TWO LAYERS — never conflate them:**
- **Layer A — how `claude` is launched** (flox `ai-agents` env supplies the
  binary + writes secrets to `~/.claude.json`). INNOCENT re: memory: it sets no
  `CLAUDE_CONFIG_DIR`, no `--settings`, doesn't touch resolution.
- **Layer B — how the harness resolves the memory dir** (cwd → slug →
  `~/.claude/projects/<slug>/memory` → today a symlink into the repo). This is
  the only layer where the memory/auto-load question lives.

**1. Auto-memory is REPO-WIDE (documented, confirmed by probe).** The `<project>`
slug is derived from the **git repository** (common-dir), so *"all worktrees and
subdirectories within the same repo share one auto memory directory."* A worktree
does NOT get its own auto-memory. Consequence: **per-branch / per-worktree memory
isolation is architecturally impossible.** A write in a worktree session lands in
the repo-root memory (proven: a "remember X" in a worktree wrote to the MAIN
checkout's `.claude/memory`). External vs nested worktree location does NOT change
this (same git common-dir → same slug).

**2. `autoMemoryDirectory` setting — VERIFIED on darwin (2026-08-14).** Docs:
absolute or `~/`-path, honored from any settings scope INCLUDING
`settings.local.json` under the workspace-trust rule, redirects **both read and
write**. Clean probe on the `refactor/claude-instructions-to-skills` worktree (no
contaminating slug symlink this time): set `autoMemoryDirectory` to the tracked
`<wt>/.claude/memory`, reloaded, asked to remember a marker → the write landed in
the **tracked** dir (git saw the new file + MEMORY.md index entry) and NO `memory/`
appeared at the default `projects/<slug>/memory`. So this is THE mechanism to pin
memory into a tracked per-worktree dir; the old `link-memory.sh` symlink is now
DELETED. (The two June probes read as UNVERIFIED only because a pre-existing slug
symlink shadowed the resolved path.)

**3. Slug derivation — still relevant for the SESSIONS bridge, not for memory.**
Claude derives the `<slug>` by replacing BOTH `/` AND `.` with `-`
(`/…/rke2lab.d/main` → `-…-rke2lab-d-main`); a `sed 's:/:-:g'` that misses the dot
computes a WRONG slug. Correct: `sed 's:[/.]:-:g'`. **Memory no longer uses the
slug at all** — `autoMemoryDirectory` (fact #2) is an explicit absolute path, and
`link-memory.sh` is DELETED. The slug still matters for `link-sessions.sh` (the
Dock-sidebar transcript bridge `~/.claude/projects/<slug>` → `<wt>/.claude/projects/<slug>`),
which keeps this derivation. See [[external-worktree-operating-model-state]].

**4. `CLAUDE_CONFIG_DIR` — exists, macOS scope UNDOCUMENTED.** Docs mention it once
(credentials, scoped to "Linux or Windows" — macOS conspicuously absent). Claimed
to relocate "every ~/.claude path" but NOT enumerated/confirmed for darwin, and
unclear whether it moves `~/.claude.json` (the secrets sibling) or the
`projects/<slug>` transcripts/memory. **Bedrock auth (SSO session `hyland`,
`CLAUDE_CODE_USE_BEDROCK=1`) removes the expert's #1 blocker** (macOS Keychain
creds) — real auth is in the AWS layer, so `~/.claude` holds no auth secrets and a
relocated config tree is safe to commit. Probe PENDING (see chantier note).

**5. macOS firmlink:** `/var` → `/private/var`; `/var/lib/...` and
`/private/var/lib/...` are the same inode. Git stores `/private/var/...` absolute
paths in worktree pointers.

See [[external-worktree-operating-model-state]] (the chantier applying these),
[[worktree-per-conversation]] (the isolation rule, now refined), [[branch-namespaces]].
