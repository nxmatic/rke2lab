---
name: bedrock-compaction-issue
description: "Auto-compaction fails with Bedrock Opus 4.8[1m], hits \"input too long\" before manual compaction possible. Checkpoint system provides recovery."
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 0be4417f-93d2-4312-a33e-ed6d5d81940c
  lastUpdated: 2026-06-05
---

Never rely on automatic compaction with the user's Bedrock setup. Proactively warn at 80k tokens. Checkpoints provide recovery if compaction fails.

**Why:** User reports "input too long for that model" errors occur BEFORE they can request manual compaction. System documentation claims auto-compaction exists, but it fails in practice with their configuration:
- Model: `us.anthropic.claude-opus-4-8[1m]` via AWS Bedrock
- The `[1m]` suffix (1-minute generation timeout) may imply reduced context window
- Bedrock's context limits may differ from Anthropic API

User has no visibility into token count and hits the limit unexpectedly.

**How to apply:**
1. At **80,000 tokens** (40% of assumed 200k budget), warn: "Context at 80k tokens — recommend compacting or starting fresh soon to avoid hitting limit"
2. At **120,000 tokens** (60%), warn again with urgency
3. When reading very large files (>5k lines), suggest closing them after use
4. After completing a major phase (refactor done, feature shipped), suggest starting a new conversation

Do NOT wait for automatic compaction to kick in — it demonstrably doesn't work in this environment.

**Recovery mechanism (enhanced 2026-06-06):**

- **Agent-authored drafts**: Claude writes `.claude/checkpoint-draft-<session_id>.md` with conversation context (task, approach, discoveries, blockers, concrete next steps) using `precompact-session-state` skill
- **Intelligent merge**: PreCompact hook merges agent context + git state → complete recovery point
- **Graceful fallback**: If no draft exists, creates git-only checkpoint (commits + status)
- **Retention**: Keeps last 3 checkpoints per session (configurable via `CLAUDE_CHECKPOINT_KEEP_LAST`)
- **Reminders**: SessionStart hook reminds Claude to maintain draft if missing or stale (30+ min)
- If compaction fails or context lost, user can resume from most recent checkpoint with full conversation context
- See `.claude/bin/README.md` and `.claude/skills/precompact-session-state.md` for details
