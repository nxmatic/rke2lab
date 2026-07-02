---
name: global-wip-guard-hooks-state
description: "Global git wip-guard hooks — generalized to any-depth wip/.wip, protects main+develop, shipped to nix-darwin-home develop; rke2lab migration + bioskop activation still pending"
metadata: 
  node_type: memory
  type: project
  originSessionId: a6f17f6c-4d13-432b-9cc0-1e4239a9efcf
---

Generalizing rke2lab's `wip/`-root-only git guard into a GLOBAL, any-depth wip-guard, deployed from **nix-darwin-home** via a global `core.hooksPath`. Triggered by a stray `c4-preview.adoc` committed in docrepo-dag-wip → user wanted a machine-wide convention forcing a conscious decision before wip reaches shared history.

**SHIPPED to nix-darwin-home `develop` 2026-06-13** (squash commit `762da360`, feature branch `feature/global-wip-guard-hooks` deleted). Brainstorm→spec→plan→subagent-driven execution (Tasks 1-5 each spec+quality reviewed, all green).

**What shipped** (5 code files under `modules/home-manager/git.d/hooks/`):
- `lib-wip-guard.sh` — detection `WIP_RE='(^|/)\.?wip/'` (segment-exact: matches `wip/`·`.wip/` at any depth; NOT `swipe/`, `wiping.md`, bare file `wip`); `PROTECTED_BRANCHES=("main" "develop")` + `is_protected_branch`; `wip_paths_in_index`/`wip_paths_in <treeish>`; `reject <paths> <branch>` (names concrete branch); `chain_repo_local <name> <self_dir>` (execs FIRST repo-local `.git/hooks/<name>` then `.githooks/<name>`, skips self → no recursion).
- `pre-commit` dispatcher (guards staged index on protected branch, then chains), `pre-push` dispatcher (guards pushed tree for `refs/heads/{main,develop}`, replays stdin, then chains).
- `tests/wip-guard-test.sh` — 14/14 green (detection ×6, block-main, block-develop, allow-feature, chain-runs, chain-skipped, push main/develop/feature). EXCLUDED from deploy via `git.nix` fileset subtraction `./git.d/hooks/tests`.
- `git.nix` — added `core.hooksPath = "${config.xdg.configHome}/git/hooks";` to `programs.git.settings`. `xdg.configFile."git"` (recursive=true) deploys hooks as sibling symlinks → `here=$(cd dirname BASH_SOURCE && pwd)` finds the lib sibling.

**develop protection** added on top of the main-only plan at user's request (develop = integration/activation branch, currently alias of main). The merge itself was the forcing function: feature branch carried spec+plan under `wip/superpowers/`, squash-merge unstaged `wip/` so **only code landed on develop** (verified wip-free). Plan was relocated `wip/plans/` → `wip/superpowers/plans/` (writing-plans defaults to docs/; [[superpowers-assets-in-wip]] says plans+specs both go in wip/). Artifacts survive untracked in the nix-darwin-home working tree.

**Design vs GitHub branch protection** (user asked): COMPLEMENTARY layers — hooks = client-side, pre-push/commit, gate on CONTENT (no wip/ path), bypassable (`--no-verify`), don't travel with clone; GitHub = server-side, gate on STATUS (reviews/checks), authoritative. Native branch protection can't express "no wip/ dir" — would need a CI check replaying lib-wip-guard.sh as a required status. PARKED (YAGNI while solo + all clones on own nix-managed machines).

**Task 7 — end-to-end verification: DONE ✓ 2026-06-13.** User ran `darwin-rebuild switch` on bioskop. Verified: pre-commit/pre-push deployed +x (lib-wip-guard.sh non-exec = correct, it's sourced); tests/ excluded; effective `core.hooksPath` → `~/.config/git/hooks` (lives in ~/.config/git/config, home-manager-managed — NOT ~/.gitconfig, so `git config --global --get` shows unset, that's expected); guard blocks on main (exit 1) + develop (banner names the branch + path), allows on feature (exit 0). Global guard is LIVE on all repos.

**STILL PENDING (1 deferred step):**
- **Task 6 — rke2lab migration: DEFERRED until the parallel rke2lab session merges to main.** A concurrent conversation is working in rke2lab; do NOT touch it. When clear: delete `rke2lab/.githooks/` (3 files), remove the `core.hooksPath .githooks` block from `.flox/env/manifest.toml` on-activate + regenerate `manifest.lock`, AND `git config --unset core.hooksPath` in the existing clone (flox engraved `.githooks` into `.git/config`; repo-local wins over global until unset). Plan = `wip/superpowers/plans/2026-06-13-global-wip-guard-hooks.md` (Task 6) in nix-darwin-home working tree. Safe interim: repo-local `.githooks` keeps winning, so rke2lab runs old guard while all other repos run the new global one — no double-guard.
- **Task 7 — end-to-end verification: DEFERRED until user runs `darwin-rebuild switch --flake .#bioskop`** (mutating, user-run). Then confirm: `~/.config/git/hooks/{pre-commit,pre-push}` deployed +x, `git config --get core.hooksPath` → `~/.config/git/hooks`, guard fires in a fresh scratch repo. If a deployed hook lacks +x, switch git.nix to per-file `xdg.configFile` entries with `executable = true`.

Risk note (from Task 5 review): a global `core.hooksPath` means git no longer auto-runs any repo's `.git/hooks/*` — they only run via the dispatcher's chain. Chain covers `.git/hooks/<name>` + `.githooks/<name>`, so husky/lefthook/project hooks still fire. Task 7 would surface any repo relying on un-chained hooks.
