---
name: working-style-narrate-progress
description: Narrate intent in one line before each tool batch; cap investigation — the user reads silent deliberation gaps as being stuck
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 28d6a117-e5b7-4227-bca3-9d64e719c38b
---

The user works in a VSCode IDE and watches tool activity live. Long silent deliberation gaps between tool batches read to them as a deadlock or "pédaler dans la semoule," even when I am actually progressing through read/grep batches.

**Why:** They can't see my internal reasoning — only emitted text and tool calls. A multi-minute gap with no visible output feels like a hang. In one session they interrupted ~4 times asking "tu es bloqué ?".

**How to apply:**
- Emit one short line *before* each tool batch stating what I'm about to do and why ("Je lis X et Y pour Z").
- Cap investigation: stop reading once I have enough to act. I over-read (8 files when ~4 sufficed) on the explode-annotation fix.
- If genuinely stuck, say so explicitly and ask — don't deliberate silently.
- A >~1 min silent gap is their cue to interrupt; interrupting costs nothing and doesn't lose my work.
