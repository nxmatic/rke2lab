---
name: osgi-runtime-decomposition-debt
description: "Refactor debt flagged by the user (2026-06-20, R4 WI-C): OsgiRuntime is getting touffu — boot() now orchestrates pax-logging + felix.scr + model bundles + embedded-vs-classpath install + Import-Package mirror + start-levels + manifest parsing. Once R4 proves the seam, consider delegating to components (e.g. an export-deriver, a bundle-source abstraction file-vs-embedded, a start-level installer). NOT done in R4 — proving the seam comes first; decomposing now would bloat the increment."
metadata:
  node_type: memory
  type: project
---

User (2026-06-20, reviewing WI-C boot()): "c'est bien mieux, on comprend mieux le role de chacun, on
verra plus tard si on peut encore mieux decouper en delegant a des composants, parce que la osgi
runtime commence vraiment a etre touffu."

**State:** `OsgiRuntime` (osgi/runtime) is one class doing a lot — `boot()` orchestrates:
- pax-logging install (slf4j single-exporter guard) + StaticLogbackContext config
- felix.scr install + ServiceComponentRuntime await
- model-bundle install
- TWO bundle sources: classpath-located `Path` jars (reactor/test) AND embedded
  `META-INF/bundles/<name>.jar` streamed into Felix's cache (deployed exec-jar)
- Import-Package → system.packages.extra mirror (manifest read from a file OR a jar stream)
- OSGi start-levels (logging=1, felix runtime=2, model bundles=3) + STARTED latch

**Debt / future direction (NOT R4):** delegate to focused components — candidates the user hinted at:
- a bundle-source abstraction (file vs embedded) so boot() stops branching on the two
- an export-deriver (the mirror logic: splitClauses / importClauseToExport / readManifestHeader)
- a start-level installer
Do it AFTER R4 proves the seam (pulumi preview). Decomposing mid-WI-C would bloat the increment and
risk the proven-green topology. Tie to [[migration-branch-no-fallback]] discipline: only refactor
what the migration needs, when it needs it.

See [[osgi-runtime-r4-resume-state]] [[r4-resolver-service-ification]].
