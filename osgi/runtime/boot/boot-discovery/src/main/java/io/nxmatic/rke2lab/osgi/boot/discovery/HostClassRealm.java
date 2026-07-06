package io.nxmatic.rke2lab.osgi.boot.discovery;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The host {@link ClassRealm} — the flat JCL the boot runs on, seen through ONE class loader. The
 * single collaborator the two facts a boot needs about its host derive from, so neither is a static
 * helper a caller re-implements:
 *
 * <ul>
 *   <li>{@link #stagedBundles()} — the bundles staged under {@code META-INF/bundles/} reachable
 *       through this loader (empty off an exec-jar);
 *   <li>{@link #resolves(String)} — whether the host can provide a package, the host-side predicate
 *       {@link BootPlanner} consults to fail fast on a {@code system.packages.extra} entry the host
 *       cannot actually provide.
 * </ul>
 *
 * <p>These are host-world QUESTIONS, not capability faces — so they stay named methods, not {@code
 * adapt(Class)} calls. As a {@link ClassRealm} the host adapts to few faces (the flat loader offers
 * little infra to hand out); {@link #adapt(Class)} keeps the self-cast default, empty otherwise —
 * semantics B, the asymmetry with the bundle realm is real, not a defect.
 *
 * <p>An instance, passed through the boot graph, so a reader sees WHERE the host-world facts come
 * from (a named loader) instead of a static reaching {@code SomeClass.class.getClassLoader()} in
 * two places. {@code boot-discovery} stays pure (JDK only): this wraps a {@link ClassLoader}, it
 * does not boot anything.
 */
public final class HostClassRealm implements ClassRealm {

  /**
   * Every package the boot module layer carries — the JDK platform/internal packages ({@code
   * java.xml}'s {@code javax.xml.*} / {@code org.w3c.dom} / {@code org.xml.sax}, {@code
   * jdk.security.auth}'s {@code com.sun.security.auth.module}, …). On Java 9+ these live in named
   * modules, NOT as directory resources of a flat classpath, so {@code getResources} cannot see
   * them — yet the system bundle serves them (its parent loader reads every boot-layer module). The
   * resolution predicate must therefore count them as host-provided; computed once, it is a
   * process-wide constant.
   */
  private static final Set<String> BOOT_MODULE_PACKAGES = bootModulePackages();

  private final ClassLoader loader;

  private HostClassRealm(ClassLoader loader) {
    this.loader = loader;
  }

  /**
   * The host realm seen through {@code loader} — the flat classpath the system bundle exports from.
   */
  public static HostClassRealm of(ClassLoader loader) {
    return new HostClassRealm(loader);
  }

  /** The bundles staged under {@code META-INF/bundles/} reachable through this loader. */
  public BundleIndex stagedBundles() {
    return BundleIndex.ofStagedBundles(loader);
  }

  /**
   * Whether the host can provide {@code packageName} — either the flat classpath carries it as a
   * directory resource, or a boot module layer package supplies it. A package neither side carries
   * cannot be wired into the framework from the system bundle. Injected into {@link BootPlanner} as
   * its host-resolution predicate.
   */
  public boolean resolves(String packageName) {
    if (BOOT_MODULE_PACKAGES.contains(packageName)) {
      return true;
    }
    final String path = packageName.replace('.', '/');
    try {
      return loader.getResources(path).hasMoreElements();
    } catch (IOException ex) {
      throw new UncheckedIOException("failed probing the host classpath for " + packageName, ex);
    }
  }

  /** Every package exported into the boot module layer — the JDK's named-module packages. */
  private static Set<String> bootModulePackages() {
    final Set<String> packages = new LinkedHashSet<>();
    for (Module module : ModuleLayer.boot().modules()) {
      packages.addAll(module.getPackages());
    }
    return packages;
  }
}
