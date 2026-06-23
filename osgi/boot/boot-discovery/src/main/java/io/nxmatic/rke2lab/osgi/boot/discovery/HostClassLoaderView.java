package io.nxmatic.rke2lab.osgi.boot.discovery;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * The host world's view through ONE class loader (the flat JCL the boot runs on) — the single
 * collaborator the two facts a boot needs about its host derive from, so neither is a static helper
 * a caller re-implements:
 *
 * <ul>
 *   <li>{@link #stagedBundles()} — the bundles staged under {@code META-INF/bundles/} reachable
 *       through this loader (empty off an exec-jar);
 *   <li>{@link #resolves(String)} — whether the flat classpath this loader sees carries a package,
 *       the host-side predicate {@link BootPlanner} consults to fail fast on a {@code
 *       system.packages.extra} entry the host cannot actually provide.
 * </ul>
 *
 * <p>An instance, passed through the boot graph, so a reader sees WHERE the host-world facts come
 * from (a named loader) instead of a static reaching {@code SomeClass.class.getClassLoader()} in
 * two places. {@code boot-discovery} stays pure (JDK only): this wraps a {@link ClassLoader}, it
 * does not boot anything.
 */
public final class HostClassLoaderView {

  private final ClassLoader loader;

  private HostClassLoaderView(ClassLoader loader) {
    this.loader = loader;
  }

  /** The host view through {@code loader} — the flat classpath the system bundle exports from. */
  public static HostClassLoaderView of(ClassLoader loader) {
    return new HostClassLoaderView(loader);
  }

  /** The bundles staged under {@code META-INF/bundles/} reachable through this loader. */
  public BundleIndex stagedBundles() {
    return BundleIndex.ofStagedBundles(loader);
  }

  /**
   * Whether this loader's flat classpath carries {@code packageName} — a package with no directory
   * resource there cannot be wired into the framework from the system bundle. Injected into {@link
   * BootPlanner} as its host-resolution predicate.
   */
  public boolean resolves(String packageName) {
    final String path = packageName.replace('.', '/');
    try {
      return loader.getResources(path).hasMoreElements();
    } catch (IOException ex) {
      throw new UncheckedIOException("failed probing the host classpath for " + packageName, ex);
    }
  }
}
