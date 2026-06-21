---
name: osgi-testkit-framework-injection-idea
description: "Future increment idea (user, 2026-06-20, NOT in R4 scope): extend the OSGi JUnit testkit (FelixFrameworkExtension) with a Jupiter ParameterResolver/TestInstancePostProcessor that injects test fixtures — and the classes they reference that define injectables — from the booted framework's registry, instead of tests constructing dependencies by hand. The user calls fixture + referenced-injectable injection 'a must'."
metadata:
  node_type: memory
  type: project
---

## The idea

The repo already has an OSGi JUnit extension (the test-only `FelixFrameworkExtension`, and now the
production `OsgiRuntime`). User's idea (2026-06-20): adjoin a **Jupiter component** (a
`ParameterResolver` and/or `TestInstancePostProcessor`) that resolves a test's dependencies from the
**booted framework's BundleContext** — so a test declares `@Test void x(Resolver r)` or an injected
field, and the framework supplies the service, rather than the test doing `new ResolverImpl(...)`.

User, verbatim (2026-06-20): "notre extension osgi, on pourrait l'etendre ou lui adjoindre un autre
composant jupiter, pour injecter les tests via le framework OSGi" and the must-have refinement:
"l'injection des classes de fixture, ainsi que des classes qu'elle reference et qui definissent des
elements injectable est un must" — i.e. inject the fixture classes AND the classes they reference
that themselves define injectable elements (transitive injectable resolution from the framework).

## Why it matters / how it'd apply

It reconciles the tension settled in [[r4-resolver-service-ification]]: today `UnitResolverTest` /
`ManifestsVisitOrderTest` / `RegistryResolveTest` inject their own `new ResolverImpl(...)` (the test
owns its dependency — correct, never the reverse). With this testkit feature they'd instead receive
the `org.osgi.service.resolver.Resolver` from the framework — exactly "Resolver is an OSGi
interface, accessed via the framework" — without imposing anything on production.

## Scope discipline (why NOT now)

This is a **testkit increment of its own** (design the extension, the shared-framework lifecycle
across tests, field/param resolution, transitive injectable discovery) — OUTSIDE the R4 critical
path (prove the boot seam). Analysis agreed: embedding the framework in the pure-algorithm unit
tests adds ZERO coverage (the ResolverImpl obtained via the framework is bit-identical; resolution
is over pure org.osgi.resource data). The real robustness win was hardening the SEAM test to drive
the SCR-injected Resolver end-to-end — already done in R4. Pick this up as a separate increment.

## Sibling idea (user, 2026-06-21): OSGi-fy jgiven + test modules as fragments

Surfaced during doctor Placement 2 commit 2. Two coupled moves:

1. *Wrap-bundle for jgiven & co* — a new module type that runs bnd-maven-plugin over the jgiven jars
   with `Export-Package: com.tngtech.jgiven.*`, so jgiven resolves IN the framework as a first-class
   bundle (rather than a plain test-classpath jar).
2. *Test modules contributed as fragments* — OSGi test bundles with a `Fragment-Host`, attached to
   the bundle under test, sharing its classloader → they see `internal`/package-private types
   white-box INSIDE the framework, and can play jgiven scenarios in-container against the real wiring.

**Decision (2026-06-21): a SEPARATE chantier, AFTER Placement 2.** Not in commit 2/3. Rationale,
measured on the code: (a) NO bundle imports jgiven in `main` — jgiven is host-only today (only the
seed-master host launcher consumes it); (b) the ~46 relocated doctor tests are bare-JVM tests, none
boot Felix, so OSGi-fying them adds zero coverage now; (c) the repo already settled "share packages
to OSGi tests" = DERIVED `system.packages.extra` (proven in `osgi/bench`), NOT wrap-bundles — a jgiven
wrap-bundle would open a SECOND competing pattern for the same need, against the uniformity discipline;
(d) there is no in-framework jgiven consumer to justify the wrap yet. Placement 2 closes the internal
edge via package-private (commit 3) independently of any of this.

When picked up, the prealable question is "system-package vs wrap-bundle" for jgiven, decided on its
own merits; the fragment-test model is a project-wide test-model pivot deserving its own handoff.

### The root cause this chantier fixes (named 2026-06-21, doctor P2 commit 2)

User's framing, confirmed: *our test modules run in the bare-JVM JCL (flat surefire classpath) yet
cover code that lives in bundles* (classloader-isolated, Import/Export-governed at runtime). The two
worlds don't match, and that mismatch produces three concrete pains — all of which the fragment-test
model would dissolve:

1. *Maven reactor cycles for shared fixtures.* On a flat classpath a shared fixture must be a separate
   Maven module, and a module cannot depend back on what tests it → `doctor-port ↔ doctor-testkit`
   when `ReferralReplies` (builds port-type `ReferralReply`) is needed by doctor-port's OWN tests.
   In OSGi this is NOT a cycle: a test FRAGMENT *is* its host (shared classloader), a fragment
   relation, no reverse dependency edge. The bundle model expresses natively what the flat classpath
   cannot. *A fragment cannot fix THIS cycle though — it is a build-time Maven cycle; the fragment
   acts at runtime, too late. So P2 contours it by fixture placement (option B), not by a fragment.*
2. *White-box by package co-location is a workaround.* commit 3 hides the 8 actors via package-private
   + tests co-located in the same package `io.nxmatic.rke2lab.doctor`. That only works because the
   flat classpath makes the package the boundary. A test fragment of doctor-core would see
   package-private types because it SHARES the host classloader — the native mechanism, not a
   co-location trick.
3. *Fidelity gap.* a bare-JVM test never sees the real Import/Export boundary: it can import a type the
   bundle does not export at runtime → green in test, broken in the framework. The test does not cover
   the code AS IT RUNS.

So this chantier is not cosmetic: it is what makes doctor's (and every domain's) tests run in the same
world as the code they cover. Doctor P2 ships in the bare-JVM model and leaves this as honest,
documented debt (see the handoff's debt section).

See [[r4-resolver-service-ification]] [[osgi-runtime-r4-resume-state]] [[doctor-internal-edge-debt]].
