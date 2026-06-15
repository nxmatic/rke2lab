---
name: docs-diagrams-not-java
description: "Project docs (incl. wip specs/plans) must use prose + C4/UML Mermaid diagrams, NEVER Java code snippets — the user reviews far faster from diagrams"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: c3b5b384-f90a-4c14-b24e-c30b941a16b6
---

The user has stated this MORE THAN ONCE ("comme déjà exprimé"): documentation in this repo — design
docs, wip specs, AND implementation plans — must NOT contain Java code snippets. Use prose plus C4 /
UML diagrams (Mermaid, per CLAUDE.md) whenever a structure/relationship/flow needs showing.

**Why:** diagrams let the user review proposals quickly; Java listings slow the review and bury the
intent. They review by skimming diagrams, not by reading code blocks.

**How to apply:**
- Design docs: prose + Mermaid (C4 context/container, class, sequence). Zero Java blocks. A UML class
  diagram already conveys an enum/record's shape — don't ALSO paste the Java.
- Implementation plans: describe each TDD step's behaviour in prose ("the test asserts X"; "add a
  method returning a flat map with keys a/b/c"), with a diagram for tricky interactions. Keep exact
  file paths and shell commands (those aren't Java and help execution). This OVERRIDES the
  writing-plans skill's "show full code in every step" rule — the user's preference wins, and the
  plan's executor (me or my subagents) has codebase context, so prose suffices.
- Shell/bash commands for build/test/verify are fine and useful — the ban is specifically on Java
  source snippets.

Related: [[shared-artifacts-in-english]] (docs/comments/commits in en-US).
