---
name: claude-auto-memory-mechanics
description: "How Claude Code resolves auto-memory + config dirs — the hard facts learned 2026-06-15. Auto-memory is REPO-WIDE (one dir per git repo, shared across all worktrees, keyed off git common-dir). autoMemoryDirectory setting: UNVERIFIED on darwin (two probes contaminated by an existing slug symlink — never cleanly tested). link-memory.sh slug = replace BOTH / and . with -. CLAUDE_CONFIG_DIR exists but macOS scope undocumented."
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

**2. `autoMemoryDirectory` setting — UNVERIFIED, not proven dead.** Docs: absolute
or `~/`-path, any settings scope, honored only after the trust dialog. We probed
twice (worktree subdir + project root); BOTH probes were CONTAMINATED — a slug
symlink already existed at the resolved path and plausibly SHADOWS the setting, so
it was never cleanly tested. Earlier "it's dead" was retracted. The clean test
(never yet run): REMOVE the slug symlink first, then set autoMemoryDirectory, then
fresh session. Reads can also be confounded — the model will read `.claude/memory`
files directly when asked, which looks like auto-load but isn't.

**3. `link-memory.sh` slug bug (FIXED 2026-06-15).** Claude derives the slug by
replacing BOTH `/` AND `.` with `-` (`/…/rke2lab.d/main` → `-…-rke2lab-d-main`).
The script did `sed 's:/:-:g'` (only `/`) → once the path had a dot it computed a
WRONG slug, made a stray dir, left the real one dangling. Fixed to
`sed 's:[/.]:-:g'`. **Fix currently lives ONLY on the uncommitted rke2lab branch
`chore/external-worktree-operating-model` (`.claude/bin/link-memory.sh`,
commit b49895fd).** The hub has NO link-memory.sh yet — needs one before the hub
itself migrates to a dotted path. See [[external-worktree-operating-model-state]].

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
