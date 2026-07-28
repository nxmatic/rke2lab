package io.nxmatic.rke2lab.maven.staging;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The embedded-boot smoke test behind the {@code REALM_WIRING_INTEGRITY} law: it BOOTS the
 * assembled exec's OWN OSGi framework and asserts the assembly is actually wireable — every bundle
 * RESOLVES and the flat/bundle export-sets stay disjoint. This is the ONE check that observes the
 * real resolver instead of inferring from manifests; it catches the failures a static scan cannot
 * see (an unsatisfied import, an unattachable fragment, an {@code osgi.ee} mismatch, a split
 * package that slipped past {@code deriveSystemExports}).
 *
 * <p>It is NOT a duplication-precision check — a booted {@code flat ∩ export} over-reports every
 * staged library, so duplication precision stays with the static {@link DuplicateRealmClass} and
 * its seam filter. This law is orthogonal: it proves the assembly boots.
 *
 * <p>The shaded uber-jar is self-contained — flat host classes + Felix + SCR + slf4j at the root,
 * plus the staged bundles under {@code META-INF/bundles} — so a child {@link URLClassLoader} over
 * it holds EXACTLY the runtime classpath. Booting {@code FrameworkLaunch.embedded().launch()}
 * THROUGH that loader (reflectively — the extension runs in Maven's realm and cannot link
 * framework-launcher) resolves the real bundle graph; the answer crosses back as JDK collections,
 * the only classes both realms share. Parent = the platform classloader, so the child sees ONLY the
 * JDK and the uber-jar, never Maven's own classes — isolation is by classloader, not by process, so
 * no fork is needed.
 */
final class BootedRealmDiagnostic {

  private static final String FRAMEWORK_LAUNCH =
      "io.nxmatic.rke2lab.osgi.runtime.framework.FrameworkLaunch";

  private final Path uberJar;

  BootedRealmDiagnostic(Path uberJar) {
    this.uberJar = uberJar;
  }

  /**
   * Boot the uber-jar's framework and return the wiring-integrity violations it exhibits: one line
   * per bundle that failed to resolve, plus one per package the system bundle and an installed
   * bundle both export (the disjointness the boot's {@code deriveSystemExports} must maintain). An
   * empty list is a clean, wireable assembly. Throws only on a catastrophic boot failure — the
   * caller turns that into its own violation, since a framework that will not boot at all is the
   * strongest breach of this law.
   */
  @SuppressWarnings("unchecked")
  List<String> observe() throws Exception {
    final ClassLoader previous = Thread.currentThread().getContextClassLoader();
    try (URLClassLoader child =
        new URLClassLoader(
            "osgi-staging-boot",
            new URL[] {uberJar.toUri().toURL()},
            ClassLoader.getPlatformClassLoader())) {
      Thread.currentThread().setContextClassLoader(child);
      final Class<?> frameworkLaunch = child.loadClass(FRAMEWORK_LAUNCH);
      final Object embedded = frameworkLaunch.getMethod("embedded").invoke(null);
      final Object booted = embedded.getClass().getMethod("launch").invoke(embedded);
      try {
        final List<String> unresolved =
            (List<String>) booted.getClass().getMethod("unresolvedBundles").invoke(booted);
        final Map<String, List<String>> duplications =
            (Map<String, List<String>>)
                booted.getClass().getMethod("realmDuplications").invoke(booted);
        return new Report(unresolved, duplications).violations();
      } finally {
        booted.getClass().getMethod("close").invoke(booted);
      }
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
  }

  /**
   * The realm facts a booted framework reports back — the bundles that never resolved, and per
   * installed bundle the packages it co-exports with the system bundle. It derives its OWN
   * wiring-integrity violation lines: one per unresolved bundle, THEN one per co-export. Modelling
   * the report as a value that owns its {@link #violations()} keeps the translation an instance
   * behaviour (unit-testable on its own, no static helper) while the live boot in {@link
   * #observe()} — exercised by the build's own {@code REALM_WIRING_INTEGRITY} run — merely feeds
   * it. An empty result is a clean, wireable assembly.
   */
  record Report(List<String> unresolvedBundles, Map<String, List<String>> realmDuplications) {
    List<String> violations() {
      final List<String> lines = new ArrayList<>();
      for (String bsn : unresolvedBundles) {
        lines.add("bundle " + bsn + " did not resolve");
      }
      realmDuplications.forEach(
          (bsn, packages) -> lines.add(bsn + " co-exports with the system bundle: " + packages));
      return lines;
    }
  }
}
