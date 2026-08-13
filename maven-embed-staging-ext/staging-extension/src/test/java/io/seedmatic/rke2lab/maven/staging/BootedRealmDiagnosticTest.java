package io.seedmatic.rke2lab.maven.staging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BootedRealmDiagnosticTest {

  @Test
  void aWireableAssemblyHasNoViolations() {
    assertTrue(
        new BootedRealmDiagnostic.Report(List.of(), Map.of()).violations().isEmpty(),
        "every bundle resolved and the export-sets are disjoint → clean");
  }

  @Test
  void anUnresolvedBundleIsReportedByName() {
    final List<String> v =
        new BootedRealmDiagnostic.Report(List.of("io.seedmatic.rke2lab.doctor"), Map.of())
            .violations();
    assertEquals(List.of("bundle io.seedmatic.rke2lab.doctor did not resolve"), v);
  }

  @Test
  void aCoExportWithTheSystemBundleIsReported() {
    final List<String> v =
        new BootedRealmDiagnostic.Report(
                List.of(), Map.of("io.seedmatic.rke2lab.manifests", List.of("org.cdk8s")))
            .violations();
    assertEquals(1, v.size());
    assertTrue(
        v.get(0).startsWith("io.seedmatic.rke2lab.manifests co-exports with the system bundle:"));
    assertTrue(v.get(0).contains("org.cdk8s"), "the offending package is named");
  }

  @Test
  void unresolvedBundlesAreListedBeforeCoExports() {
    final Map<String, List<String>> duplications = new LinkedHashMap<>();
    duplications.put("io.seedmatic.rke2lab.manifests", List.of("org.cdk8s"));
    final List<String> v =
        new BootedRealmDiagnostic.Report(List.of("io.seedmatic.rke2lab.doctor"), duplications)
            .violations();
    assertEquals(2, v.size());
    assertTrue(v.get(0).endsWith("did not resolve"), "resolution failures come first");
    assertTrue(v.get(1).contains("co-exports"), "co-exports follow");
  }

  @Test
  void aJarWithoutTheFrameworkLaunchClassMakesObserveThrow() throws Exception {
    // The caller (enforceGates) turns a catastrophic boot failure into its own violation — the
    // "framework that will not boot at all" is the strongest breach of the law. Here the child
    // loader over a jar with no FrameworkLaunch cannot even load the entry point.
    final BootedRealmDiagnostic diagnostic = new BootedRealmDiagnostic(Path.of("no-such-exec.jar"));
    assertThrows(Exception.class, diagnostic::observe);
  }
}
