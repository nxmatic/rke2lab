package io.nxmatic.rke2lab.osgi.boot.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves the boot DECISION without booting Felix — the whole point of telling {@link BootPlanner}
 * apart from the launcher. Each test stages manifest-only jars under a temp {@code
 * META-INF/bundles/} reached by a {@link URLClassLoader}, builds a real {@link BundleIndex}, and
 * asserts the {@link BootPlan} the planner computes (start levels, {@code system.packages.extra},
 * the seam guard) — pure value assertions, no framework, no SCR.
 */
final class BootPlannerTest {

  private static final String EMBED = BundleManifest.EMBED_CAPABILITY_NAMESPACE;

  @Test
  void mirrorsModelImportsHostFlatIntoSystemExports(@TempDir Path dir) throws IOException {
    // A model bundle that imports a host-flat library package (jackson) and exports its own domain
    // package. The import is mirrored into system.packages.extra; the self-exported domain package
    // is NOT (the bundle is its sole provider).
    stage(
        dir,
        "model.jar",
        Map.of(
            "Bundle-SymbolicName", "com.example.model",
            "Provide-Capability", EMBED + ";type=model",
            "Export-Package", "com.example.model.api",
            "Import-Package", "com.fasterxml.jackson.databind"));

    final BootPlan plan = planOf(dir, p -> true); // host carries jackson

    assertTrue(
        plan.systemPackagesExtra().stream().anyMatch(e -> e.startsWith("com.fasterxml.jackson")),
        "the model's host-flat import is mirrored as a system export");
    assertTrue(
        plan.systemPackagesExtra().stream().noneMatch(e -> e.startsWith("com.example.model.api")),
        "the model's own exported package is NOT system-exported (it is the sole provider)");
  }

  @Test
  void seamGuardThrowsNamingPackageAndOwningBundleOnDomainLeak(@TempDir Path dir)
      throws IOException {
    // Installed model imports a domain package owned by ANOTHER domain bundle that the policy does
    // NOT install. The package would land in system.packages.extra with its owner absent — a leak.
    stage(
        dir,
        "consumer.jar",
        Map.of(
            "Bundle-SymbolicName", "com.example.consumer",
            "Provide-Capability", EMBED + ";type=model",
            "Import-Package", "com.example.secret.domain"));
    stage(
        dir,
        "owner.jar",
        Map.of(
            "Bundle-SymbolicName", "com.example.owner",
            "Provide-Capability", EMBED + ";type=model",
            "Export-Package", "com.example.secret.domain"));

    final BundleIndex index = stagedIndex(dir);
    final BootRequest request =
        BootRequest.create()
            .embedBootStack()
            .discover(DiscoveryPolicy.only("com.example.consumer"));

    final IllegalStateException leak =
        assertThrows(
            IllegalStateException.class, () -> new BootPlanner(index, p -> true).plan(request));
    assertTrue(leak.getMessage().contains("com.example.secret.domain"), "names the leaked package");
    assertTrue(leak.getMessage().contains("com.example.owner"), "names the owning domain bundle");
  }

  @Test
  void closesOverFelixScrImportsPullingTheSpecJarAtPassiveLevel(@TempDir Path dir)
      throws IOException {
    // felix.scr (a boot-stack jar, matched by symbolic name) imports a DS-API package that no
    // installed bundle exports and the host does not carry flat. The closure pulls in the spec jar
    // that exports it, PASSIVELY (lowest level) so it resolves before felix.scr activates.
    stage(
        dir,
        "felix-scr.jar",
        Map.of(
            "Bundle-SymbolicName", "org.apache.felix.scr",
            "Import-Package", "org.osgi.service.component"));
    stage(
        dir,
        "ds-api.jar",
        Map.of(
            "Bundle-SymbolicName", "org.osgi.service.component",
            "Export-Package", "org.osgi.service.component"));

    // host does NOT carry the DS-API package flat → it cannot be system-exported, must be a bundle.
    final BootPlan plan = planOf(dir, p -> !p.startsWith("org.osgi.service.component"));

    final BootPlan.Installable specJar =
        plan.installables().stream()
            .filter(i -> i.location().locationId().endsWith("ds-api.jar"))
            .findFirst()
            .orElseThrow(
                () -> new AssertionError("the DS-API spec jar was not pulled into the plan"));
    assertEquals(
        BootPlan.START_LEVEL_PASSIVE,
        specJar.startLevel(),
        "a passive spec jar resolves before the boot stack that imports it");
  }

  // --- helpers -----------------------------------------------------------------------------------

  private static BootPlan planOf(Path stagingDir, Predicate<String> hostResolves)
      throws IOException {
    final BundleIndex index = stagedIndex(stagingDir);
    return new BootPlanner(index, hostResolves).plan(BootRequest.create().embedBootStack());
  }

  private static BundleIndex stagedIndex(Path stagingDir) throws IOException {
    final URLClassLoader loader = new URLClassLoader(new URL[] {stagingDir.toUri().toURL()}, null);
    return BundleIndex.ofStagedBundles(loader);
  }

  /** Write a manifest-only jar under {@code <dir>/META-INF/bundles/<name>}. */
  private static void stage(Path dir, String name, Map<String, String> headers) throws IOException {
    final Path bundles = dir.resolve("META-INF/bundles");
    Files.createDirectories(bundles);
    final Manifest manifest = new Manifest();
    final Attributes main = manifest.getMainAttributes();
    main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
    new LinkedHashMap<>(headers).forEach((k, v) -> main.put(new Attributes.Name(k), v));
    try (OutputStream out = Files.newOutputStream(bundles.resolve(name));
        JarOutputStream _ = new JarOutputStream(out, manifest)) {
      // manifest-only jar — JarOutputStream writes META-INF/MANIFEST.MF from the manifest argument.
    }
  }
}
