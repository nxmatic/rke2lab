---
name: mermaid-label-constraints
description: "Mermaid DSL label constraints that broke a whiteboard render (2026-07-09). The rules to respect when authoring C4/Mermaid figures in .claude/claude-preview.adoc and the specs — chiefly: never nest escaped double-quotes inside a quoted label."
metadata:
  type: feedback
---

The user caught Mermaid render errors in the whiteboard (2026-07-09): "tu ne dois pas avoir lu les
contraintes liées au DSL mermaid." The defect: I put `@DocumentContract(\"readiness-verdict\")` — escaped
double-quotes INSIDE a label already delimited by `"..."`. Mermaid does not parse nested escaped quotes;
the whole diagram fails.

**Rules (verified against the shipped specs' Mermaid, e.g. seed-broker-spec.adoc):**

+ **Never nest `\"` inside a quoted label.** Use single quotes `'...'` for any inner quoting:
  `a2["@DocumentContract('readiness-verdict')"]` — NOT `a2["...\"readiness-verdict\"..."]`.
+ **Always wrap a label in `"..."` when it contains ANY special char** — then `·`, `→`, `*`, `/`, `()`,
  `:` are all safe inside. The specs quote every non-trivial label for this reason.
+ Line breaks in labels: `<br/>` (the specs use it), not `\n`.
+ Edge labels `|"..."|` follow the same quoting rule as node labels `["..."]`.
+ **Never use a Mermaid reserved word as a node ID** — `graph`, `end`, `style`, `class`, `subgraph`,
  `flowchart`. `graph["…"]` breaks the parse (it reads `graph` as the diagram-type keyword expecting
  `graph LR`). Rename the node (`pgraph`, `theGraph`). Caught 2026-07-14 on the cellar whiteboard.
+ **Never put angle-brackets `<...>` in ANY label** — a Java generic like `Consumer<Store>` in a
  sequenceDiagram message (or any node/edge label) is read as an HTML tag and 400s the render (pptr.dev
  "Error 400"). Caught 2026-07-18 on the runbook-handler whiteboard. Drop the type parameter (`seed
  Consumer`) or say it in prose. Same class: sequenceDiagram message text also rejects `()` and `:` —
  write `handle cellar, trigger`, not `handle(cellar, trigger)`. Keep message labels plain words.

**How to apply:** when authoring/editing any Mermaid block, grep the diff for `\"` before saving — a
nested escaped quote is the classic breaker. Prefer apostrophes for inner quoting. See
[[options-always-as-c4-diagrams]] [[collaborative-design-method]].
