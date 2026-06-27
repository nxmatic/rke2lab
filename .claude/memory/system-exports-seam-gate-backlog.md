---
name: system-exports-seam-gate-backlog
description: A future build-gate (4th staging law) — guard that FRAMEWORK_SYSTEMPACKAGES_EXTRA only ever carries seam packages (type=seam), never a domain concern that should wire bundle-to-bundle. The automatic guardian of the cdk8s-style "two worlds separate" rule we applied by hand.
metadata:
  type: project
---

**Backlog (user, 2026-06-26): "la prochaine build-gate, ce sont les framework system exports."** A
4th staging law beside RECORD_PURITY / SPEC_COVERAGE / INSTANCE_DISCIPLINE: assert that every package
the framework system-exports (`FRAMEWORK_SYSTEMPACKAGES_EXTRA`, derived by `BootPlanner.deriveSystemExports`
from the model bundles' Import-Package) is a legitimate SEAM — a `type=seam` `-port` membrane the host
and OSGi worlds genuinely share one copy of — and NEVER a domain concern that should resolve
bundle-to-bundle.

**Why now:** this is exactly the cdk8s trap we fixed by hand today ([[cdk8s-carrier-flat-jar-pattern]]).
cdk8s/constructs were leaking host-flat through system-packages; we moved them onto the bundle side
(carrier with Bundle-ClassPath + Export-Package). `deriveSystemExports` already has the seam-guard logic
(it removes bundle-exported packages, keeps only what resolves to null = a real seam), and the comment
there names the failure mode ("would split the class against the bundle's own copy"). The gate would
make that guard a BUILD-TIME assertion with a visible count, not a runtime-only invariant — so a new
flat-jar leak fails the build instead of silently splitting a class at boot.

**Shape (sketch):** read the staged model bundles' Import-Package closure, compute what would land in
system-packages, and flag any entry whose owning bundle is `type=model`/`type=edge` (a domain bundle
is the sole exporter → must be wired, never system-exported). Only `type=seam` packages (and genuine
JDK/host seams) may appear. Mirrors [[osgi-system-export-resolution-only]] (system-export ⟺ "NOT
designed for OSGi") as a checkable law.

**The model call (user, 2026-06-26):** "jgiven, cdk8s ils doivent arriver dans le classpath et pas via
les system exports — c'était une facilité qu'on a utilisée, mais un mauvais choix de modèle." So
system-exports-for-flat-jars is recognised DEBT, not a legitimate pattern: the easy lever (drop the
package in `system.packages.extra`) was the wrong model. The right model is the carrier
Bundle-ClassPath ([[cdk8s-carrier-flat-jar-pattern]]). Two concrete migrations this gate would protect
once done: cdk8s (DONE, d5a221fa) and jGiven → pipeline ([[jgiven-domain-into-pipeline-debt]], the
jgiven-wrap is itself a flat-jar carrier the gate would keep honest). The gate's job is to stop anyone
(including me) reaching for the facilité again — a non-seam package in system-exports = build break.

One of three gate ideas now queued: this one, extend INSTANCE_DISCIPLINE to host space
([[instance-discipline-gate-misses-host-backlog]]), and the dependency-analyze gate
([[dependency-analyze-gate-backlog]]). See [[build-gates-over-review-reminders]]
[[spec-coverage-gate-state]] [[bundle-on-jcl-is-wrong-classpath]].
