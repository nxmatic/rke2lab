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

See [[r4-resolver-service-ification]] [[osgi-runtime-r4-resume-state]].
