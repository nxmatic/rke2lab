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

**The decorative spinner words actively STRESS the user (restated 2026-06-07).** The harness
spinner shows vague gerunds ("elucidating / musing / effecting / Wandering / Germinating /
Noodling") that say nothing about the actual task. The user finds them meaningless AND
stress-inducing — they cannot tell from them whether I am progressing or stuck. I do not choose or
control these words; they are the harness's own decoration. The ONLY signal I control is the prose I
emit before/between tool batches, so that prose must carry the whole "what + how far" load. The user
asked that I ALWAYS state what I'm about to do and how I'm progressing.

**How to apply:**
- Emit one short line *before* each tool batch stating what I'm about to do and why ("Je lis X et Y pour Z").
- Emit a *progress* line on multi-step work ("3/5 corrections done, now the 4th"). Keep the TodoWrite list live — it is a visible progress surface that counters the empty spinner.
- **Think OUT LOUD during design deliberation too, not just before tool batches.** When weighing a design choice (e.g. jointure vs auto-suffisant for the medical record), narrate the reasoning as prose — show the alternatives, the tension, where I land and why. Silent deliberation on a *decision* reads as stuck just like a silent tool gap does.
- Never chain several tool calls in silence — the gap with only the spinner showing is exactly what stresses them.
- Cap investigation: stop reading once I have enough to act. I over-read (8 files when ~4 sufficed) on the explode-annotation fix.
- If genuinely stuck, say so explicitly and ask — don't deliberate silently.
- A >~1 min silent gap is their cue to interrupt; interrupting costs nothing and doesn't lose my work.

**This is a STANDING, RECURRING violation, not a one-off (restated with visible frustration 2026-06-20,
rke2lab R4):** "à chaque fois c'est la même chose, tu pars dans des raisonnements et tu me laisses dans
le noir." The danger zone is precisely the BIG read/carto/exploration phases — under that load I tend to
chain several tool calls (esp. parallel Agent/Explore fan-outs) with no prose, then dump a wall of text
at the end. The rule inverts exactly there: when a large investigation phase is coming, SLOW DOWN and
narrate more (one line per batch, running progress), do not speed up silently. The reasoning itself is
wanted — what's not wanted is doing it without keeping them in the loop.
