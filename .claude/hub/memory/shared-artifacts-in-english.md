---
name: shared-artifacts-in-english
description: "All shared/checked-in artifacts must be written in American English, never French"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 28d6a117-e5b7-4227-bca3-9d64e719c38b
---

Everything that is **shared or checked into the repo** must be written in American English: documentation (`docs/*.adoc`), code comments, Javadoc, commit messages, PR descriptions, file content.

**Why:** The user writes to me in English but is a French native; shared artifacts are read by others / serve as durable reference, so they must be in English regardless of the conversation language. Conversational replies to the user can stay in French, but anything persisted to the repo must not.

**How to apply:** When authoring any `docs/` file, comment, or commit message, write in en-US. If I catch myself writing French in a checked-in artifact, fix it before committing. Watch this especially in AsciiDoc design docs — I slipped French into `docs/rke2-install-phases.adoc` (section bodies + Mermaid notes) on 2026-06-03; the user corrects in parallel.

Related: [[rke2lab:rke2-install-phases]] design doc (the one that had the French slip).
