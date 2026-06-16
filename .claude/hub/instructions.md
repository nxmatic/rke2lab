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

- When in a git repository and a task is about to **mutate** files (edit, commit, or any write), enter a dedicated worktree first — `EnterWorktree`, default `baseRef: fresh` so it branches from `origin/<default-branch>`, a clean current base isolated from other sessions' dirty trees. Pure read-only / investigation questions stay in the current checkout; don't spin up a throwaway worktree for them.
- Treat the main checkout as read-only reference, not a workspace. Never `git checkout <other-branch>` in it — another session may be living there.
- Keep `origin/<default-branch>` the source of truth for the base: fast-forward and push the default branch when integrating, so the next conversation's `fresh` worktree starts from current work.
- Per-repo gotchas (e.g. flox `[include]` manifest-relative paths that don't resolve from inside `.claude/worktrees/…`) live in project memory — consult it before re-locking or composing includes from a worktree.

## Context window management

When the conversation context grows beyond 150,000 tokens, warn me proactively. Say something brief like "Context approaching limit (150k+ tokens used) — consider summarizing or starting fresh if we shift topics." This prevents work loss from hitting the hard limit unexpectedly.
