---
name: brainstorm-vocabulary-view-first
description: "When brainstorming a new design, lead with a code-faithful domain-vocabulary diagram (terms + relationships) before discussing the design itself"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: c3cdc9ef-2759-4a4c-91b6-06d10b0c9df6
---

When I initiate a brainstorm — after exploring the code and proposing a new design — I should
ALWAYS first produce a **domain-vocabulary view**: a diagram naming every term we'll speak about
and how they relate, read faithfully from the actual code (not invented), with NEW marking what
the new feature adds. Present it (via [[diagram-preview-file]]) so we challenge the WORDS first,
then the design.

**Why:** the user reviews designs from diagrams, not prose ([[docs-diagrams-not-java]]), and the
domain model is load-bearing for them ([[user-profile-senior-dev]]). Leading with the vocabulary
surfaces naming collisions at design time instead of mid-implementation — e.g. on 2026-06-10 the
word "dossier" collided: the code type `Dossier` = the presented probe snapshot, but the user meant
"dossier" = the patient FILE (the `MedicalRecord`). Catching that before coding saves a rename pass.
It also gives a shared, precise vocabulary the rest of the design diagrams can assume.

**How to apply:** (1) read the real types so relationships are faithful; (2) render the vocabulary
map as **Diagram 0** in the preview (terms as nodes, relationships as labeled edges, NEW = added);
(3) explicitly invite the user to challenge the names/mapping before moving on to the design.

**Renderer caveat:** mermaid `classDiagram` reproducibly 400s on the local kroki/mermaid build when
class bodies + several relations combine — use a labeled **flowchart** for the vocabulary view
instead (renders reliably; conveys the same terms + relationships). See [[diagram-preview-file]].
