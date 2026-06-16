# User preferences

## Language

The user is a French citizen; English is not their first language and they describe their spoken English as still developing. They write to me in English regardless.

When the user's phrasing is unclear, ambiguous, or grammatically off in a way that could change the meaning, restate it back more clearly before acting on it — both to confirm I understood the intent and to help them improve their English. Keep the rephrasing brief and natural; do not lecture, over-correct, or flag every minor slip. The goal is mutual clarity and gentle language help, not a grammar audit.

When the intent is already clear, just proceed — no need to rephrase.

## Progress narration

The harness spinner shows vague decorative words ("elucidating", "musing", "effecting", and similar) that I neither choose nor control and that say nothing about the actual task. They stress me: I can't tell from them whether you are working or stuck. So the prose you emit is the only real signal, and it must always carry it:

- Before each tool batch, state in one line what you are about to do and why.
- On multi-step work, give progress as you go ("3/5 done, now the 4th"); keep the todo list live.
- Never chain several tool calls in silence — a silent gap with only the spinner showing is exactly what stresses me.
- Do not rely on the spinner words to convey progress; narrate in real prose instead.
- If you are genuinely stuck, say so and ask — don't deliberate silently.

## Code hygiene

Never leave dead code behind. When a change supersedes an old path — a function, a code branch, a delivery mechanism, a config knob — delete the old one entirely and update all call sites in the same change. Do not defer removal to "a later pass," do not leave it as "dead weight, not breakage," and do not keep it for backwards-compatibility unless explicitly asked. If a refactor makes something unused, that unused thing is part of the same refactor's scope.

## Workspace isolation

Each conversation works in its OWN git worktree — never share a worktree or branch checkout between parallel conversations. (Born from a real collision: two sessions mutating the same checkout leaked one's uncommitted pom edits into the other's working tree.)

**Worktrees are EXTERNAL, NOT under `.claude/worktrees/`.** This repo family uses the external-worktree operating model: every checkout lives at `<repo>.d/<namespace>/<branch>` — a sibling of `main` — so one VSCode window = indexed code + chat, and so flox `[include]` manifest-relative paths resolve (they do NOT resolve from inside `.claude/worktrees/…`). **Do NOT use the `EnterWorktree` harness tool** — it hard-codes the `.claude/worktrees/<branch>` location, which this model rejects. Use plain `git worktree add` at the external path instead.

- When in a git repository and a task is about to **mutate** files (edit, commit, or any write), create a dedicated external worktree first. Pure read-only / investigation questions stay in the current checkout; don't spin one up for them. The startup recipe (run from `<repo>.d/main`):
  1. `git fetch origin <default-branch>` — base must be fresh `origin/<default-branch>`, a clean base isolated from other sessions' dirty trees.
  2. `git worktree add -b <namespace>/<slug> <repo>.d/<namespace>/<slug> origin/<default-branch>` — namespace by kind (`feature/`, `chore/`, `design/`, `refactor/`, `spike/`).
  3. **Re-smudge sops** — `git worktree add` checks files out BEFORE `.sops.yaml` is visible to the smudge filter, so sops-governed files land ENCRYPTED. Fix per repo memory (`sops-worktree-smudge-noise`): for each still-encrypted sops file, `rm <file> && git checkout -- <file>`; verify no real secret still contains `ENC[` (mind false positives — docs/code/schemas may mention `ENC[` as text).
  4. `cd` into the new worktree for the rest of the session.
- Treat the main checkout as read-only reference, not a workspace. Never `git checkout <other-branch>` in it — another session may be living there.
- Keep `origin/<default-branch>` the source of truth for the base: fast-forward and push the default branch when integrating, so the next conversation's external worktree starts from current work.
- At finish, removing a merged `<repo>.d/<namespace>/<branch>` worktree is the normal expected gesture (it is user-managed under THIS model, not harness-owned) — do not refuse on provenance grounds; see repo memory (`external-worktree-operating-model-state`) for the full cleanup recipe.
- Per-repo gotchas (the flox `[include]` constraint above, the sops re-smudge, Pulumi-state-per-worktree) live in project memory — consult it before re-locking or composing includes from a worktree.

## Context window management

When the conversation context grows beyond 150,000 tokens, warn me proactively. Say something brief like "Context approaching limit (150k+ tokens used) — consider summarizing or starting fresh if we shift topics." This prevents work loss from hitting the hard limit unexpectedly.
