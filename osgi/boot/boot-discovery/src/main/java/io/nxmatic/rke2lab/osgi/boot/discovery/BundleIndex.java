package io.nxmatic.rke2lab.osgi.boot.discovery;

import io.nxmatic.rke2lab.osgi.bnd.EmbedCapability;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;

/**
 * The bundles a boot can see, each read ONCE into a {@link BundleManifest} and queried as an
 * instance — the single source of truth for "where is bundle X" before the framework exists. ONE
 * index over two SOURCES (the only thing that differs is how a source enumerates its bundles and
 * how each is read — captured by {@link BundleLocation}):
 *
 * <ul>
 *   <li>{@link #ofClasspath()} — the {@code java.class.path} entries (reactor builds, before the
 *       exec-jar embeds its jars). A process singleton: the classpath is fixed for the JVM's life.
 *   <li>{@link #ofStagedBundles(ClassLoader)} — the jars staged under {@code META-INF/bundles/} in
 *       a deployed exec-jar, reached through the classloader that carries them.
 * </ul>
 *
 * <p>A bundle is found by what its MANIFEST declares, never guessed from a file name. Two honest
 * keys: {@link #locateBySymbolicName} by {@code Bundle-SymbolicName} (jars we cannot mark — felix,
 * pax, junit), and {@link #matching(String)} by an LDAP filter over the {@link EmbedCapability
 * embed capability} attributes (OUR bundles, which self-declare what they are).
 */
public final class BundleIndex {

  /** A scanned bundle: where it lives plus the headers it declares, both read ONCE at scan time. */
  private record Entry(BundleLocation location, BundleManifest manifest) {}

  private static final class ClasspathHolder {
    private static final BundleIndex INSTANCE = scan(classpathLocations());
  }

  /** The index over this JVM's {@code java.class.path}, scanned on first access and cached. */
  public static BundleIndex ofClasspath() {
    return ClasspathHolder.INSTANCE;
  }

  /**
   * The index over the bundles staged under {@code META-INF/bundles/} reachable through {@code
   * loader} — the deployed exec-jar's embedded stack (or the exploded resource root on a reactor
   * test classpath). Empty when nothing is staged (the off-exec-jar degraded case).
   */
  public static BundleIndex ofStagedBundles(ClassLoader loader) {
    try {
      return scan(stagedLocations(loader));
    } catch (IOException ex) {
      throw new IllegalStateException("cannot enumerate staged bundles", ex);
    }
  }

  private final List<Entry> entries;

  private BundleIndex(List<Entry> entries) {
    this.entries = List.copyOf(entries);
  }

  private static BundleIndex scan(List<BundleLocation> locations) {
    final List<Entry> found = new ArrayList<>();
    for (BundleLocation location : locations) {
      try {
        final BundleManifest manifest = BundleManifest.from(location);
        if (manifest.symbolicName() == null && manifest.embed() == null) {
          continue; // not a bundle we can install.
        }
        if (location.isFrameworkLauncher()) {
          continue; // the launcher becomes system bundle 0, never an installed bundle.
        }
        found.add(new Entry(location, manifest));
      } catch (IOException ex) {
        // An unreadable entry is not a bundle we can install — skip it.
      }
    }
    return new BundleIndex(found);
  }

  /**
   * The bundle declaring {@code symbolicName} as its {@code Bundle-SymbolicName}. The way to locate
   * a bundle we do NOT own (felix, pax, junit) — by the identity it publishes, exact, not by a file
   * name.
   */
  public BundleLocation locateBySymbolicName(String symbolicName) {
    return entries.stream()
        .filter(e -> symbolicName.equals(e.manifest().symbolicName()))
        .map(Entry::location)
        .findFirst()
        .orElseThrow(
            () -> new IllegalStateException("no bundle " + symbolicName + " in " + describe()));
  }

  /**
   * Every bundle whose {@link EmbedCapability embed capability} matches {@code ldapFilter} — e.g.
   * {@code (type=model)} for the boot's model bundles, or {@code
   * (&(type=fixture)(suite=scr)(role=consumer))} for one test fixture. Selection is by what each
   * bundle DECLARES, so a caller never keeps a name list in sync; an empty match is a legitimate
   * result (the anti-cheat installs a consumer with no provider by filtering it out). Sorted by
   * symbolic name for a reproducible boot order.
   */
  public List<BundleLocation> matching(String ldapFilter) {
    final Filter filter = filter(ldapFilter);
    return entries.stream()
        .filter(e -> e.manifest().embed() != null && e.manifest().embed().matches(filter))
        .sorted(Comparator.comparing(e -> nullToEmpty(e.manifest().symbolicName())))
        .map(Entry::location)
        .toList();
  }

  /**
   * Whether any bundle declares {@code symbolicName} — the "is this an exec-jar with X staged"
   * probe.
   */
  public boolean contains(String symbolicName) {
    return entries.stream().anyMatch(e -> symbolicName.equals(e.manifest().symbolicName()));
  }

  /**
   * Every installable bundle in the index, in scan order — the launcher already excluded (it is the
   * system bundle), so this is exactly the set a {@link DiscoveryPolicy#all()} boot installs.
   */
  public List<BundleLocation> all() {
    return entries.stream().map(Entry::location).toList();
  }

  /** The symbolic name a location declares, for a policy filtering the index by name. */
  String symbolicNameOf(BundleLocation location) {
    return entries.stream()
        .filter(e -> e.location().equals(location))
        .map(e -> e.manifest().symbolicName())
        .findFirst()
        .orElse(null);
  }

  /**
   * A bundle in the index that exports {@code packageName} but is neither a seam (system-exported,
   * host-flat) — or {@code null}. The runtime closure's query: felix.scr imports {@code
   * org.osgi.service.component}, no installed bundle provides it, so this pulls in the dedicated
   * spec jar that exports it. The launcher is already absent from the index. First match wins; a
   * genuine multi-exporter conflict is the developer's classpath to keep clean, as at build time.
   */
  public BundleLocation exporterOf(String packageName) {
    return entries.stream()
        .filter(e -> e.manifest().embed() == null || !e.manifest().embed().isSeam())
        .filter(e -> e.manifest().exports().names().contains(packageName))
        .map(Entry::location)
        .findFirst()
        .orElse(null);
  }

  /** The parsed manifest of a location in this index — for an executor pinning its start level. */
  public BundleManifest manifestOf(BundleLocation location) {
    return entries.stream()
        .filter(e -> e.location().equals(location))
        .map(Entry::manifest)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("location not in index: " + location));
  }

  /**
   * The {@code Import-Package} of bundle {@code symbolicName} mirrored into the system-bundle
   * export clauses it needs to resolve against a flat classpath.
   */
  public Set<String> exportsForImportsOf(String symbolicName) {
    return entries.stream()
        .filter(e -> symbolicName.equals(e.manifest().symbolicName()))
        .map(e -> e.manifest().imports().asSystemExports())
        .findFirst()
        .orElseThrow(
            () -> new IllegalStateException("no bundle " + symbolicName + " in " + describe()));
  }

  /**
   * The symbolic name of the DOMAIN bundle ({@code type=model}/{@code edge}) that exports {@code
   * packageName}, or {@code null} if no domain bundle in the index exports it. The leak guard's
   * core query: a non-null answer means {@code packageName} loads on the BUNDLE side of the seam,
   * so it must NEVER be system-exported — were the system bundle to also export it, the host's flat
   * copy and the bundle's copy would split the class. A seam ({@code type=seam}) package, or one
   * exported by a non-embed library, answers {@code null} — legitimately system-exportable. Scanned
   * over the whole classpath, so a domain package whose exporter is NOT in the install set is still
   * recognised (exactly the leak we forbid).
   */
  public String domainExporterOf(String packageName) {
    return entries.stream()
        .filter(e -> e.manifest().embed() != null && e.manifest().embed().isDomain())
        .filter(e -> e.manifest().exports().names().contains(packageName))
        .map(e -> e.manifest().symbolicName())
        .findFirst()
        .orElse(null);
  }

  private String describe() {
    return entries.size() + " indexed bundle(s)";
  }

  private static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }

  private static List<BundleLocation> classpathLocations() {
    final List<BundleLocation> locations = new ArrayList<>();
    for (String element :
        System.getProperty("java.class.path").split(System.getProperty("path.separator"))) {
      final Path path = Path.of(element);
      final boolean isBundle =
          Files.isDirectory(path)
              ? Files.exists(path.resolve("META-INF/MANIFEST.MF"))
              : Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar");
      if (isBundle) {
        locations.add(new BundleLocation.OnClasspath(path));
      }
    }
    return locations;
  }

  /** Resource prefix a deployed exec-jar stages the designed-for-OSGi jars INTACT under. */
  static final String STAGED_ROOT = "META-INF/bundles/";

  private static List<BundleLocation> stagedLocations(ClassLoader loader) throws IOException {
    final URL root = loader.getResource(STAGED_ROOT);
    if (root == null) {
      return List.of();
    }
    final List<BundleLocation> locations = new ArrayList<>();
    if ("jar".equals(root.getProtocol())) {
      final JarURLConnection connection = (JarURLConnection) root.openConnection();
      try (var jar = connection.getJarFile()) {
        for (var entry : java.util.Collections.list(jar.entries())) {
          final String name = entry.getName();
          if (!entry.isDirectory()
              && name.startsWith(STAGED_ROOT)
              && name.endsWith(".jar")
              && name.indexOf('/', STAGED_ROOT.length()) < 0) {
            locations.add(new BundleLocation.Staged(loader, name));
          }
        }
      }
    } else {
      final Path dir;
      try {
        dir = Path.of(root.toURI());
      } catch (URISyntaxException ex) {
        throw new IOException("malformed staged-bundles root URL: " + root, ex);
      }
      try (var stream = Files.newDirectoryStream(dir, "*.jar")) {
        for (Path jar : stream) {
          locations.add(new BundleLocation.Staged(loader, STAGED_ROOT + jar.getFileName()));
        }
      }
    }
    return locations;
  }

  private static Filter filter(String ldapFilter) {
    try {
      return FrameworkUtil.createFilter(ldapFilter);
    } catch (InvalidSyntaxException ex) {
      throw new IllegalArgumentException("malformed LDAP filter: " + ldapFilter, ex);
    }
  }
}
