---
name: manifests-tests-pre-osgi-debt
description: manifests-core tests are pre-OSGI POJO/flat-classpath tests (new Default…()), not the in-container fragment-test model doctor uses. A debt to migrate now that manifests uses DS correctly — exercise the @Component services in-container (injected) instead of constructing them by hand.
metadata:
  type: project
---

`manifests-core` was written BEFORE the OSGi migration, so its tests live in `src/test/java` as
flat-classpath POJO tests that `new DefaultManifestExplodeService(...)` etc. doctor, written
OSGi-native, instead uses the in-container **fragment-test** model: a `-test` Fragment-Host bundle
whose actor `@Test` classes live in `src/main/java` (white-box on the host loader), driven by a
bare-JVM probe that boots Felix and runs them inside the framework, acquiring `@Component` services by
`FrameworkUtil.getBundle(getClass()).getBundleContext().getServiceReference(...)`.

**Why this is debt (the user's framing):** running these tests in JCL/flat-classpath makes the fixtures
heavier — every test hand-wires what DS would inject (the `new YamlMapper()` in
`DefaultManifestExplodeServiceTest` is the live symptom). Now that manifests uses DS correctly (the
`YamlMapper` `@Component`, the services `@Reference`-injected — see [[refactor-statics-on-touch]] and the
YamlMapper commit), the in-container model becomes natural: the test gets the service injected, no `new`.

**How to apply (blueprint exists, mirror doctor exactly — uniformity):**
- New module `osgi/manifests/manifests-core-test`, parent `bundle-test-parent`, `Fragment-Host:
  io.nxmatic.rke2lab.manifests.core`, `Provide-Capability: io.nxmatic.rke2lab.embed; type=fixture;
  suite=manifests; role=core`. Deps: manifests-core (provided), manifests-port (provided), DS
  annotations (provided), jgiven-testkit/jgiven-wrap/byte-buddy/osgi.core (test).
- Actor `@Test` classes → `src/main/java/io/nxmatic/rke2lab/manifests/` (compiled into the fragment);
  a `ManifestsCoreTests` runner (names `JupiterTestEngine.class`, delegates to `InContainerJUnitRunner`).
- The bare-JVM probe `ManifestsCoreInContainerTest` in `src/test/java`: `JGivenTestkit.felix()`
  `.systemPackages("io.nxmatic.rke2lab.systemd.port;version=1.0.0")` `.installFromClasspath(junit
  stack + manifests-port + other domain ports as bundles + junit-testkit)` `.build()`, install fixture
  by filter, resolve host, start, reflectively run the runner.
- Tests that exercise a `@Component` (e.g. DefaultManifestExplodeService, the YamlMapper) acquire it via
  `getServiceReference` (use `withScr()`), NOT `new`. Pure-API tests can stay POJO in src/test.
- Add `manifests-core-test` to `osgi/manifests/pom.xml` modules.

Full investigated blueprint (doctor exemplar paths, pom/bnd verbatim) was produced 2026-06-26; the model
to copy is `osgi/doctor/doctor-core-test/` + `osgi/jgiven/jgiven-testkit/`. See
[[object-graph-navigability-principle]] (why DS injection beats hand-wired fixtures).
