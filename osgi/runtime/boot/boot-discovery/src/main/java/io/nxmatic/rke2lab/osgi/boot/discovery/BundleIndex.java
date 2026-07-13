package io.nxmatic.rke2lab.osgi.boot.discovery;

import io.nxmatic.rke2lab.osgi.bnd.Clause;
import io.nxmatic.rke2lab.osgi.bnd.EmbedCapability;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
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
        final Optional<BundleManifest> manifest = BundleManifest.from(location);
        if (manifest.isEmpty()) {
          continue; // not a bundle we can install.
        }
        if (location.isFrameworkLauncher()) {
          continue; // the launcher becomes system bundle 0, never an installed bundle.
        }
        found.add(new Entry(location, manifest.orElseThrow()));
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
        .filter(e -> e.manifest().symbolicName().map(symbolicName::equals).orElse(false))
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
        .filter(e -> e.manifest().embed().map(embed -> embed.matches(filter)).orElse(false))
        .sorted(Comparator.comparing(e -> e.manifest().symbolicName().orElse("")))
        .map(Entry::location)
        .toList();
  }

  /**
   * Whether any bundle declares {@code symbolicName} — the "is this an exec-jar with X staged"
   * probe.
   */
  public boolean contains(String symbolicName) {
    return entries.stream()
        .anyMatch(e -> e.manifest().symbolicName().map(symbolicName::equals).orElse(false));
  }

  /**
   * Every installable bundle in the index, in scan order — the launcher already excluded (it is the
   * system bundle), so this is exactly the set a {@link DiscoveryPolicy#all()} boot installs.
   */
  public List<BundleLocation> all() {
    return entries.stream().map(Entry::location).toList();
  }

  /** The symbolic name a location declares, for a policy filtering the index by name. */
  Optional<String> symbolicNameOf(BundleLocation location) {
    return entries.stream()
        .filter(e -> e.location().equals(location))
        .findFirst()
        .flatMap(e -> e.manifest().symbolicName());
  }

  /**
   * A bundle in the index that exports {@code packageName} but is neither a seam (system-exported,
   * host-flat) — or {@code null}. The runtime closure's query: felix.scr imports {@code
   * org.osgi.service.component}, no installed bundle provides it, so this pulls in the dedicated
   * spec jar that exports it. The launcher is already absent from the index. First match wins; a
   * genuine multi-exporter conflict is the developer's classpath to keep clean, as at build time.
   */
  public Optional<BundleLocation> exporterOf(String packageName) {
    return entries.stream()
        .filter(e -> e.manifest().embed().map(embed -> !embed.isSeam()).orElse(true))
        .filter(e -> e.manifest().exports().names().contains(packageName))
        .map(Entry::location)
        .findFirst();
  }

  /**
   * Close over the passive jars the {@code seeds} transitively need but neither already provide nor
   * resolve host-flat — the import-closure FRAME both boot executors share, with the per-jar action
   * CONTRIBUTED as {@code onPulled}. Starting from each seed's manifest, for every MANDATORY import
   * not in {@code alreadyProvided} (a package already served: exported by a seed, by a
   * previously-pulled jar, or by the system bundle), pull in the index jar that exports it ({@link
   * #exporterOf}, which skips seams and the launcher), hand it to {@code onPulled}, and close over
   * ITS imports in turn — a fixpoint, so a second hop (util.promise → util.function) is not missed.
   * Each pulled jar is handed to {@code onPulled} exactly once, in discovery order; no seed is.
   *
   * <p>The single source of the felix.scr → DS-API-trio derivation. The two executors differ ONLY
   * in what they contribute per jar: {@code BootPlanner} adds a passive-level {@code Installable}
   * to its plan; the test {@code OutOfContainerFrameworkExtension} installs the jar into the
   * framework. The seam law lives in {@code exporterOf} (a seam is host-flat, never pulled), so the
   * walk stays pure.
   */
  public void closeOverImports(
      List<BundleLocation> seeds, Set<String> alreadyProvided, Consumer<BundleLocation> onPulled) {
    final Set<String> provided = new LinkedHashSet<>(alreadyProvided);
    final Set<String> pulledIds = new LinkedHashSet<>();
    final Deque<BundleManifest> frontier = new ArrayDeque<>();
    for (BundleLocation seed : seeds) {
      final BundleManifest manifest = manifestOf(seed);
      pulledIds.add(seed.locationId());
      provided.addAll(manifest.exports().names());
      frontier.add(manifest);
    }
    while (!frontier.isEmpty()) {
      for (Clause imported : frontier.removeFirst().imports().clauses()) {
        final String pkg = imported.name();
        if ("optional".equals(imported.attributes().get("resolution")) || provided.contains(pkg)) {
          continue;
        }
        final Optional<BundleLocation> exporter = exporterOf(pkg);
        if (exporter.isEmpty() || !pulledIds.add(exporter.orElseThrow().locationId())) {
          continue;
        }
        final BundleLocation pulled = exporter.orElseThrow();
        final BundleManifest exporterManifest = manifestOf(pulled);
        provided.addAll(exporterManifest.exports().names());
        onPulled.accept(pulled);
        frontier.add(exporterManifest);
      }
    }
  }

  /**
   * A non-seam bundle in the index that PROVIDES the service {@code objectClass} (its {@code
   * Provide-Capability: osgi.service}), or empty. The service twin of {@link #exporterOf}: where
   * that answers "who exports this package", this answers "who publishes this service" — the
   * dependency the resolver ignores (bnd marks a {@code @Reference} requirement {@code
   * effective:=active}), so a service-closure must resolve it explicitly. First match wins.
   */
  public Optional<BundleLocation> serviceProviderOf(String objectClass) {
    return entries.stream()
        .filter(e -> e.manifest().embed().map(embed -> !embed.isSeam()).orElse(true))
        .filter(e -> e.manifest().providedServices().contains(objectClass))
        .map(Entry::location)
        .findFirst();
  }

  /**
   * Close over the service PROVIDERS the {@code seeds} transitively need: for every MANDATORY
   * {@code Require-Capability: osgi.service} not already provided by an installed bundle, pull the
   * index bundle that publishes it ({@link #serviceProviderOf}), hand it to {@code onPulled}, and
   * close over ITS required services in turn — a fixpoint, so a provider that itself references
   * another service is not missed. Each provider is pulled once, in discovery order; no seed is.
   *
   * <p>The service analogue of {@link #closeOverImports}, kept SEPARATE and test-only: the prod
   * {@code BootPlanner} installs the whole deployed bundle set from its plan, so it never needs to
   * DISCOVER a provider — only the out-of-container test extension, installing a minimal host
   * graph, must chase the {@code effective:=active} service requirements the resolver skips. {@code
   * alreadyProvided} is the objectClasses installed bundles already publish (so a provider on the
   * host graph is not re-pulled). Package imports of a pulled provider are NOT followed here — the
   * caller runs {@link #closeOverImports} over the pulled set for that.
   */
  public void closeOverServices(
      List<BundleLocation> seeds, Set<String> alreadyProvided, Consumer<BundleLocation> onPulled) {
    final Set<String> provided = new LinkedHashSet<>(alreadyProvided);
    final Set<String> pulledIds = new LinkedHashSet<>();
    final Deque<BundleManifest> frontier = new ArrayDeque<>();
    for (BundleLocation seed : seeds) {
      final BundleManifest manifest = manifestOf(seed);
      pulledIds.add(seed.locationId());
      provided.addAll(manifest.providedServices());
      frontier.add(manifest);
    }
    while (!frontier.isEmpty()) {
      for (String required : frontier.removeFirst().requiredServices()) {
        if (provided.contains(required)) {
          continue;
        }
        final Optional<BundleLocation> provider = serviceProviderOf(required);
        if (provider.isEmpty() || !pulledIds.add(provider.orElseThrow().locationId())) {
          continue;
        }
        final BundleLocation pulled = provider.orElseThrow();
        final BundleManifest providerManifest = manifestOf(pulled);
        provided.addAll(providerManifest.providedServices());
        onPulled.accept(pulled);
        frontier.add(providerManifest);
      }
    }
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
        .filter(e -> e.manifest().symbolicName().map(symbolicName::equals).orElse(false))
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
  public Optional<String> domainExporterOf(String packageName) {
    return entries.stream()
        .filter(e -> e.manifest().embed().map(EmbedCapability::isDomain).orElse(false))
        .filter(e -> e.manifest().exports().names().contains(packageName))
        .findFirst()
        .flatMap(e -> e.manifest().symbolicName());
  }

  private String describe() {
    return entries.size() + " indexed bundle(s)";
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
        for (var entry : Collections.list(jar.entries())) {
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
