---
name: classrealm-adaptable-pattern
description: "The ClassRealm pattern (socle, being built 2026-07-06): a classloader-bounded world materialized as a type that offers capability faces via Optional<T> adapt(Class<T>). Unifies two pre-existing, unnamed mechanisms — HostClassRealm (flat JCL, ex-HostClassLoaderView) + a coming BundleClassRealm (delegates to Bundle.adapt) — under one contract. Named ClassRealm in the Plexus ClassWorlds sense (classloader-isolated space), NOT the security sense. Emerged mid ClusterSeed migration; ClusterSeed suspended to build it because it's socle."
metadata:
  type: project
---

**What & why.** A recurring need surfaced (the user felt it 3×): "get face T from a given world". It was
real but anonymous+scattered — `HostClassLoaderView` (host), `OsgiConnection`/`Bundle.adapt` (OSGi),
`ConsultingService.adapt` (domain). Materialize the shared contract so a reader lands on ONE door.

**The contract (promoted, not invented).** `ConsultingService.adapt` already had the exact shape —
`default <T> Optional<T> adapt(Class<T> type) { return type.isInstance(this) ? Optional.of(type.cast(this)) : Optional.empty(); }`.
Promoted to `interface ClassRealm` in `boot-discovery` (pure module: osgi.core + JDK, visible host AND
OSGi, `scenario-engine` already depends on it — ideal foyer).

**Naming (settled after real investigation).** `Realm` alone = false-positive security connotation
(Tomcat/JAAS users+roles). But the term traces to **Plexus ClassWorlds** (`ClassRealm` = an isolated
classloader; `plexus-classworlds` IS in Maven's own boot) — the sense the specs already use ("a class
references only types reachable in its own classloader realm", `DuplicateRealmClass`, `REALM_BOUNDARY`
gate). So `ClassRealm` is idiomatic + anchors the classloader meaning; the `Class` prefix kills the
security reading. No collision (Plexus `ClassRealm` never on our compile classpath). Renamed
`HostClassLoaderView` → `HostClassRealm implements ClassRealm`.

**Semantics B (settled).** A world adapts what it HAS, `Optional.empty()` otherwise. Asymmetry is REAL,
not a defect: host realm offers few faces, OSGi realm many. `empty` is the correct answer, not a gap.

**Migration line — INVENT NOTHING.** Migrate to `ClassRealm.adapt` only what is ALREADY a `.adapt(Class)`
OSGi call: `JUnitLauncherCore.wiringOf` (the `instanceof BundleReference` selector → becomes the entry
point) + `bundle.adapt(...)`. Do NOT twist `HostClassRealm.resolves()/stagedBundles()` into
`adapt(Capability.class)` — those are host-world QUESTIONS, not faces. They stay named methods.

**Increments.** A (now, socle): `ClassRealm` + `HostClassRealm`(renamed) + `BundleClassRealm` +
entry-point `of(loader)` selecting via `instanceof BundleReference`; migrate the real `.adapt` call-sites;
`OsgiConnection` adapts its domain services. Future (documented, not built): the realm as the single door
to EVERYTHING a world offers (infra AND domain services fused) — extended via `adapt(NewFace.class)` when
a real client appears (the `Adaptable` mechanism IS the extension tool — no door closed).

**Progress (2026-07-06).** Commit `00c2441`: `ClassRealm` interface + `HostClassRealm` rename + test
(ClassRealmTest 2/2 green) + `FrameworkLaunchPipeline` updated. NEXT: `BundleClassRealm`, then `of(loader)`
selector, migrate call-sites, tests both worlds. See [[engine-lifecycle-socle-state]]
[[cluster-seed-execution-state]] [[single-source-of-truth-before-logic]].
