---
name: osgi-staging-extension-chantier
description: "SCOPE for the NEXT chantier (its own worktree/session): a build-time mechanism that single-sources the shade-exclude <-> META-INF/bundles staging duplication in the exec-jars, derived from what bundles DECLARE rather than two hand-maintained pom lists. Founding test case: the DS-API crack (org.osgi.service.component/util.promise/util.function — imported by felix.scr but neither flattened nor staged today). Comes AFTER the osgi-boot-alignment seam chantier (see [[osgi-boot-alignment-state]]). NOT YET STARTED — this is the design brief."
metadata:
  node_type: memory
  type: project
---

## Why this chantier exists

Two pom faces describe the SAME fact — "which jars are real OSGi bundles that boot inside Felix vs
flat libraries the host uses directly" — and they are maintained BY HAND, so they drift:

1. `maven-shade-plugin` `<artifactSet><excludes>` — jars kept OUT of the flat uber-jar.
2. `maven-dependency-plugin` `stage-embedded-bundles` `<artifactItem>` — jars copied INTACT under
   `META-INF/bundles/` for `OsgiRuntime` to install.

A jar that should be a bundle must appear in BOTH lists; miss one and it breaks. This is the last
"hand-list in disguise" the osgi-boot-alignment chantier did not close (it closed the Java side —
capability scan, no bundle-name literals — but the pom side remained, explicitly the irreducible
remainder). Both exec-jars (seed-master + the 2 CLIs) carry the duplication.

## The defect that proves it (FOUNDING TEST CASE + validation gate)

The **DS-API crack**, found 2026-06-22:
- felix.scr IMPORTS `org.osgi.service.component;version=[1.5,2)`, `org.osgi.util.promise;[1.1,..)`,
  `org.osgi.util.function` as MANDATORY (verified in its manifest). It does NOT embed them.
- Those 3 are real bundles (each has a Bundle-SymbolicName + versioned Export-Package: component
  1.5.1, promise 1.3.0, function 1.2.0).
- In the prod uber-jar they are NEITHER flattened (0 `org/osgi/service/component/*.class`) NOR staged
  under META-INF/bundles. They fall through the crack — a human staged felix.scr/pax/resolver by hand
  and missed felix.scr's transitive DS-API import.
- Today it "works" only because `OsgiRuntime.SCR_API_PACKAGES` system-exports those packages — but
  that is a MIS-DIAGNOSIS papering over the crack: the packages are framework-internal (the flat host
  reads `ServiceComponentRuntime` BY NAME, never typed → NOT a seam), so they should be STAGED as
  bundles and resolved bundle-to-bundle, NOT system-exported.

**Gate:** the extension is correct when it spontaneously stages the 3 DS-API jars and
`SCR_API_PACKAGES` can be DELETED from both OsgiRuntime and FelixFrameworkExtension with the embedded
boot still green (HostSeamEmbeddedFelixTest + EmbeddedBundlesBootTest). That is the "retombe sur nos
pieds" proof. Do NOT hand-fix the crack first — fixing it by hand re-implements what the extension
must derive (same trap we avoided with the deleted manifests-core spike).

## Design — settled this session (read before coding)

### The discriminator is a CLOSURE, not "has a BSN"
jackson / netty / cdk8s / guava HAVE Bundle-SymbolicNames but the host consumes them FLAT (Pulumi /
cdk8s code calls them directly, outside the framework). Wrapping them would break the flat host. So
"exclude every jar with a BSN" is the SAME over-reach as "every BSN → install" that we rejected for
the -port seam.

The real rule — a closure over the framework's needs:
1. SEED = our embeddable bundles (embed-capability `type=model|edge`) + the boot-stack
   (felix.framework/scr/resolver, pax-logging-api/logback — the `BootStackJar` registry).
2. CLOSE transitively over the `Import-Package` / `Require-Capability` of those bundles. felix.scr's
   import of DS-API pulls the 3 spec jars INTO the closure — and only it (jackson etc. stay out).
3. A jar in the closure that is itself a real bundle (has a BSN) → STAGE intact + shade-exclude.
   Everything else → flat (shaded).

### seam vs stage — by READING bnd headers, NOT java reflection
(User explicitly rejected reflection: "il faut faire de la reflection java" → NO. The chantier rule
is "read what bnd declared, don't compute".) bnd already did the bytecode import analysis at build
and wrote `Import-Package`. So:
- A package a bundle imports that ALSO is exported by an installed bundle → wired bundle-to-bundle.
- A package the flat host shares typed (the `-port`) → seam, system-exported. OUR bundles DECLARE
  this (`type=seam`) — no calc.
- The DS-API trio: imported by felix.scr, host touches it by NAME only → not seam → stage it.

### The membre-gauche that does NOT exist (a corrected mis-step)
I initially thought we needed `Import-Package(host)` to compute `seam = Import(host) ∩
Export(bundles)`. WRONG, and verified: seed-master is NOT a bundle (no bnd, no bnd.bnd, the shaded
uber-jar has no Import-Package/Bundle-SymbolicName header at all). The calculation does NOT need the
host's imports — `OsgiRuntime.deriveSystemExports` already works from the BUNDLES' Import-Package
(membre droit), never the host's. The staging decision likewise is "closure over bundle imports",
host-independent. Do not chase host bnd-analysis — it is a dead end.

### "Are we recoding Equinox?" — no, but know the boundary
The exec-jar IS an OSGi runner (like the Felix/Equinox launcher). Felix already AUTO-computes
`org.osgi.framework.system.packages` (JRE packages by detected EE profile, from its packaged
`default.properties`) — we get that for free, never hand-list JRE packages. What no launcher does is
bridge a FLAT host classloader to the framework (our hybrid topology — the seam). That bridge is
ours to own; the system.packages.extra it needs is already derived by deriveSystemExports. The
extension only decides STAGE-vs-FLAT at build time; it does not re-implement a launcher.

CORROBORATED by the Felix launching-and-embedding doc (read 2026-06-22), which matches our design
point-for-point and means NOTHING is missing in our boot:
- Host shares classes with bundles via `org.osgi.framework.system.packages.extra` — that IS our
  seam, and the doc's caveat "host and bundle must use the SAME class definitions for the service
  interface" is verbatim our seam law (one package = one exporter = one class). So system.packages.
  extra is for SHARED INTERFACES only — which is exactly why the DS-API trio does NOT belong there
  (felix.scr-internal, never shared typed with the host) → stage it, an independent confirmation of
  the gate.
- Felix has NO auto-export of host packages (must be listed) → deriveSystemExports IS our way to
  produce that list; we are not missing a built-in.
- The doc shares host SERVICES via `felix.systembundle.activators` + a HostActivator — Felix-SPECIFIC.
  We deliberately do NOT use it: we read services via `framework.getBundleContext()` + ServiceTracker
  (`awaitService`), which is portable OSGi, not Felix-locked. A point in our favour, not a gap.
- Factory via `ServiceLoader.load(FrameworkFactory).newFramework(config).init/start` is the canonical
  embedding pattern — both our executors already do exactly this. Verdict: nothing to add to the boot.

## Shape constraints (the hard part, why it's a separate chantier)
- A Maven core extension (`AbstractMavenLifecycleParticipant`) loads BEFORE the reactor it governs,
  so it cannot be a reactor module — same no-parent / SEPARATE-ROOT shape as the BOM. Lives beside
  the BOM, not inside `osgi/`.
- "Never `mvn install`" friction: a core extension must be resolvable before the reactor builds.
  Design how it is provided without polluting ~/.m2 (this is the genuinely tricky bit — spend the
  first part of the new session here).
- It reuses `BundleIndex`'s logic (scan manifests, read BSN + capability + Import/Export) but at
  BUILD time, over the dependency set, not the runtime classpath. Consider whether boot-discovery's
  classes can be shared with the extension or must be duplicated (extension classloader is isolated).

## Sequence agreed with the user
1. (THIS handoff) scope #4 — done.
2. User relays to the pre-integration workspace; THIS worktree (refactor/osgi-boot-alignment) is
   parked — intact, green, integrable as-is (the seam chantier stands on its own).
3. A NEW dedicated worktree/session designs + builds the extension.
4. THEN come back to the exec-jars: let the extension place the bundles; verify the DS-API gate;
   delete SCR_API_PACKAGES.

## Related
[[osgi-boot-alignment-state]] (the seam chantier this follows; its commit c96fe6c5 doc "The boot
face" defines seam vs domain) · [[boot-pipeline-unification-backlog]] (symmetric executor-unification,
also deferred) · [[single-source-of-truth-before-logic]].
