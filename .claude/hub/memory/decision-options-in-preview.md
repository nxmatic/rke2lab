---
name: decision-options-in-preview
description: "For non-trivial design choices, render the competing options as comparative diagrams in the preview file BEFORE asking the user to choose"
metadata:
  node_type: memory
  type: feedback
  originSessionId: fad25661-6d06-4825-8ce2-6e3bbdbbafd5
---

When I ask the user to choose between non-trivial design options (anything beyond a trivial
either/or), I must FIRST render the competing options as comparative diagrams in the preview file
([[diagram-preview-file]] = `.claude/claude-preview.adoc`), THEN ask. The user chooses against
something they can *see*, side by side, not just prose options in a terminal question.

**Why:** the user reviews designs from diagrams, not prose ([[docs-diagrams-not-java]],
[[brainstorm-vocabulary-view-first]]). Asked 2026-06-15: "when asking me for such complex option,
you should provide me in the preview the different options I'm required to choose — that will make
us more confident about my choices." Choosing blind from prose is lower-confidence; a visual
comparison makes the trade-off legible and the decision firmer. Also keeps me honest — if I can't
draw the options faithfully, I don't understand them well enough to ask yet.

**How to apply:** (1) for each option, draw a small flowchart showing how that option shapes the
model (one diagram per option, or a split view); (2) overwrite `.claude/claude-preview.adoc` with
them, kroki-safe dialect per [[diagram-preview-file]]; (3) THEN use AskUserQuestion, referencing
what is on screen. Authored in American English (content may feed real docs later). This composes
with [[brainstorm-vocabulary-view-first]] (vocabulary view first, then option views, then the
chosen design).
