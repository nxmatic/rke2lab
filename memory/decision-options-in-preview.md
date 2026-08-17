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
them — DEFAULT NOW: render them as a **Claude artifact** (self-contained HTML + mermaid, per
[[diagram-preview-file]]); the `.claude/claude-preview.adoc` kroki preview is the offline fallback;
(3) THEN use AskUserQuestion, referencing what is on screen. Authored in American English (content may feed real docs later). This composes
with [[brainstorm-vocabulary-view-first]] (vocabulary view first, then option views, then the
chosen design).

**★ LANGUAGE + VOCABULARY DISCIPLINE (reinforced 2026-06-16, the user said it twice).** Previews are
NOT just "in English" — they must use the *exact American-English vocabulary of the code and docs*, so
we are certain we speak about the SAME thing and never diverge from what a figure will say when it
later feeds the real docs. Two distinct requirements, both load-bearing:
(a) *Language* — write the prose AND the node/edge labels in American English. (I repeatedly slipped
    into French this session — `synthèse`, `couche de médiation`, `actualise`, `déclencheur` — that is
    the failure mode to avoid. Every preview, every label, en-US.)
(b) *Vocabulary fidelity* — use the real identifiers as they appear in code/docs (`UnitResolver`,
    `ResourceDescription`, `ComponentResource`, `requireAll`, `mediation seam`, `north-bound`/
    `south-bound`, `manifests-model`), NOT translated paraphrases. A French paraphrase of a code term
    reads as agreement while quietly diverging; the figure then won't reconcile with the doc/code when
    it migrates. If a term doesn't yet exist in code, coin it in en-US and reuse it verbatim thereafter.
Why: the user reviews from diagrams and the diagrams seed the durable docs ([[docs-diagrams-not-java]],
[[shared-artifacts-in-english]]); a language/vocabulary gap between preview and doc forces a silent
re-translation exactly when a figure graduates. Composes with [[brainstorm-vocabulary-view-first]]
(the vocabulary view is read faithfully from the code — same en-US-identifiers rule).
