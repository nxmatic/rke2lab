---
name: user-profile-senior-dev
description: "Who the user is as an engineer (≈40 yrs experience) and how to collaborate accordingly — go deep, justify don't lecture, expect orthogonal-axis reasoning"
metadata: 
  node_type: memory
  type: user
  originSessionId: 4d3d8a2e-f292-4cbe-a699-fb4abfbd1e6c
---

The user is a developer with ~40 years of experience (stated 2026-06-08). It shows, and it should
shape how I work with them from session one:

- **Reasons in invariants and orthogonal axes.** During the error-handling design they separated
  "retryable-vs-not" from "checked-vs-unchecked" before I did, and caught that my proposed common
  exception root had silently bundled two independent decisions. Don't hand-wave; expect them to factor
  a problem into its real axes and to notice when I conflate them.
- **Wary of patterns applied on autopilot.** They said they apply patterns they practice "automatically,
  without seeing their real reach in the system" — and value being reminded of the WHY. So: never cite a
  pattern as justification by itself; argue from THIS system's needs. They enjoy having an automatism
  re-examined, not parroted.
- **Domain modelling is load-bearing, not decoration.** Their medical metaphor (record = enrichment of a
  diagnosis, not a precondition; doctor/specialist/referral) actually drives technical decisions (it's
  what settled checked-exceptions). Engage the model seriously.
- **Deepest pain = errors deported to logs and onto the human.** Their strongest reactions came from the
  anti-pattern where a defect is only a log line and the human must reconstruct context at 3am. This is
  an ops scar, not a beginner's gap. (See [[error-handling-layered-contract]].)

**How to collaborate (positive dynamic they explicitly value):** they said working this way "entirely
changed how I do my work, in a positive way." The dynamic that works: I implement FAST and hand back
CONCRETE code to challenge ([[works-best-from-concrete-code]]); that concreteness reawakens the WHY
behind 40-year automatisms; they in turn force me to JUSTIFY instead of apply. We are each other's guard
rail. So: skip basics-pedagogy, go straight to the substance, propose precise options, and DEFEND my
choices honestly (they respect a reasoned push-back and dislike rubber-stamping — I should say when I
think they're wrong, and concede cleanly when they're right, as happened repeatedly).

**On the model/memory question (they asked, mildly sceptical of how Anthropic uses conversations):** I
was honest that my weights don't learn from our chats; continuity lives only in these memory files (their
repo, their control). They appreciated the candour. Keep that honesty — don't overclaim "learning."
