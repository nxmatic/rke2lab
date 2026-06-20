package io.nxmatic.rke2lab.osgi.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;
import org.osgi.framework.startlevel.BundleStartLevel;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Boots an embedded Apache Felix framework + felix.scr inside an exec entrypoint and installs a set
 * of intact bundles, so the host can read their {@code @Component} services from the registry. The
 * runtime-side counterpart of the test-only {@code FelixFrameworkExtension}.
 *
 * <p>Built through {@link #builder()}, which DECLARES the topology: which felix runtime jars to
 * install (felix.scr is implied by {@link Builder#withScr()}), and which bundle jars to install.
 * The {@code system.packages.extra} the bundles need is DERIVED, not hand-listed: the runtime reads
 * each bundle's bnd-computed {@code Import-Package} header and re-exports those packages from the
 * system bundle, so the bundle resolves against the host's flat classpath (jackson, cdk8s, the
 * {@code -port} contracts) while sharing ONE copy of each class — typed access across the seam, no
 * reflection. This keeps the export set in lock-step with what bnd actually computed and makes each
 * exec entrypoint declare only ITS bundles; the derivation is uniform across entrypoints.
 *
 * <p>A package designed for OSGi may instead be installed as its own bundle; a package NOT designed
 * for OSGi (e.g. the jsii {@code org.cdk8s}/{@code software.constructs} jars) is served flat via
 * the derived system export. A system export carries CLASSES only — never the origin's
 * SCR/capability/ metatype behaviour — so only passive, non-OSGi packages belong there.
 */
public final class OsgiRuntime implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(OsgiRuntime.class);

  /**
   * OSGi start levels drive activation order natively — we install everything, pin each bundle to
   * its layer, then let the framework raise its level and start bundles level-by-level. Logging is
   * lowest so the LogService is live before anything else activates; the felix runtime (scr,
   * resolver) next; the model bundles last (their {@code @Component}s log + bind through the live
   * LogService and resolved runtime). The framework's own beginning level is set to the highest so
   * a single {@code framework.start()} walks all three.
   */
  private static final int START_LEVEL_LOGGING = 1;

  private static final int START_LEVEL_FRAMEWORK_RUNTIME = 2;

  private static final int START_LEVEL_BUNDLES = 3;

  /**
   * DS-runtime API packages felix.scr imports as MANDATORY that the {@code osgi.core} system bundle
   * does not carry; {@link Builder#withScr()} exports them from the system bundle.
   */
  private static final String SCR_API_PACKAGES =
      "org.osgi.service.component;version=1.5,"
          + "org.osgi.service.component.runtime;version=1.5,"
          + "org.osgi.service.component.runtime.dto;version=1.5,"
          + "org.osgi.util.promise;version=1.3,"
          + "org.osgi.util.function;version=1.2";

  /**
   * Classpath resource prefix under which the deployed exec-jar stages the designed-for-OSGi jars
   * INTACT (seed-master's {@code maven-dependency-plugin} {@code copy} execution). The {@code
   * embedded*} builder verbs name a jar under here; {@link #boot()} streams its bytes straight into
   * Felix's bundle cache via {@code installBundle(location, stream)} and reads its manifest from
   * the same stream for the Import-Package mirror — no temp file, Felix owns the persisted copy.
   */
  public static final String EMBEDDED_BUNDLES_ROOT = "/META-INF/bundles/";

  private final List<Path> bundleJars;
  private final List<String> runtimeJars;
  private final boolean startScr;
  private final List<Path> paxLoggingJars;
  private final List<String> embeddedBundleNames;
  private final List<String> embeddedRuntimeNames;
  private final List<String> embeddedPaxLoggingNames;
  private final Set<String> explicitSystemPackages;

  private Framework framework;

  private OsgiRuntime(Builder builder) {
    this.bundleJars = List.copyOf(builder.bundleJars);
    this.runtimeJars = List.copyOf(builder.runtimeJars);
    this.startScr = builder.startScr;
    this.paxLoggingJars = List.copyOf(builder.paxLoggingJars);
    this.embeddedBundleNames = List.copyOf(builder.embeddedBundleNames);
    this.embeddedRuntimeNames = List.copyOf(builder.embeddedRuntimeNames);
    this.embeddedPaxLoggingNames = List.copyOf(builder.embeddedPaxLoggingNames);
    this.explicitSystemPackages = new LinkedHashSet<>(builder.systemPackages);
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Whether the running process carries embedded bundles under {@link #EMBEDDED_BUNDLES_ROOT} —
   * true in the deployed exec-jar, false on a reactor/test classpath. The seam picks the embedded
   * boot topology over the classpath-located one with this.
   */
  public static boolean hasEmbeddedBundles() {
    return OsgiRuntime.class.getResource(EMBEDDED_BUNDLES_ROOT + "manifests-core.jar") != null;
  }

  /**
   * Locate, on {@code java.class.path}, the jar or exploded {@code target/classes} directory whose
   * path contains {@code artifact} and carries a bundle manifest. Used to resolve the felix runtime
   * jars and — during reactor builds, before the exec-jar embeds them — the bundle locations.
   */
  public static Path locateOnClasspath(String artifact) {
    return java.util.Arrays.stream(
            System.getProperty("java.class.path").split(System.getProperty("path.separator")))
        .map(Path::of)
        .filter(
            p ->
                p.toString().contains(artifact)
                    && (Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar")
                        || Files.isDirectory(p) && Files.exists(p.resolve("META-INF/MANIFEST.MF"))))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("no " + artifact + " bundle on classpath"));
  }

  /** Declares the framework topology booted by {@link #boot()}. */
  public static final class Builder {
    private final List<Path> bundleJars = new ArrayList<>();
    private final List<String> runtimeJars = new ArrayList<>();
    private final List<String> systemPackages = new ArrayList<>();
    private final List<Path> paxLoggingJars = new ArrayList<>();
    private final List<String> embeddedBundleNames = new ArrayList<>();
    private final List<String> embeddedRuntimeNames = new ArrayList<>();
    private final List<String> embeddedPaxLoggingNames = new ArrayList<>();
    private boolean startScr;

    /** Install + start felix.scr before the bundles and export the DS-runtime API it needs. */
    public Builder withScr() {
      this.startScr = true;
      this.systemPackages.add(SCR_API_PACKAGES);
      return this;
    }

    /**
     * Install + start Pax Logging FIRST (before felix.scr and the bundles) so the OSGi LogService
     * is live before anything logs. {@code paxLoggingApi} provides {@code org.slf4j} to bundles (so
     * the runtime stops system-exporting it); {@code paxLoggingLogback} is the LogService backend
     * that — with {@code StaticLogbackContext=true} — reuses the HOST's logback context. Order
     * matters: api first, then the backend.
     */
    public Builder withPaxLogging(Path paxLoggingApi, Path paxLoggingLogback) {
      this.paxLoggingJars.add(paxLoggingApi);
      this.paxLoggingJars.add(paxLoggingLogback);
      return this;
    }

    /** A felix runtime jar (e.g. {@code org.apache.felix.scr}) to install + start, by file path. */
    public Builder runtimeJar(Path jar) {
      this.runtimeJars.add(jar.toString());
      return this;
    }

    /**
     * A bundle jar to install + start; its {@code Import-Package} is mirrored as a system export.
     */
    public Builder bundle(Path jar) {
      this.bundleJars.add(jar);
      return this;
    }

    /**
     * Like {@link #bundle(Path)} but the jar is embedded in the running exec-jar under {@link
     * #EMBEDDED_BUNDLES_ROOT}; {@code name} is its staged file name (e.g. {@code
     * "manifests-core.jar"}). {@link #boot()} extracts it, then installs it identically.
     */
    public Builder embeddedBundle(String name) {
      this.embeddedBundleNames.add(name);
      return this;
    }

    /**
     * Like {@link #runtimeJar(Path)} but resolved from the embedded {@link #EMBEDDED_BUNDLES_ROOT}.
     */
    public Builder embeddedRuntimeJar(String name) {
      this.embeddedRuntimeNames.add(name);
      return this;
    }

    /**
     * Like {@link #withPaxLogging(Path, Path)} but both jars are embedded; api first, then backend.
     */
    public Builder embeddedPaxLogging(String paxLoggingApiName, String paxLoggingLogbackName) {
      this.embeddedPaxLoggingNames.add(paxLoggingApiName);
      this.embeddedPaxLoggingNames.add(paxLoggingLogbackName);
      return this;
    }

    /** Extra packages to export from the system bundle, beyond those derived from the bundles. */
    public Builder systemPackages(String... packages) {
      for (String pkg : packages) {
        this.systemPackages.add(pkg);
      }
      return this;
    }

    public OsgiRuntime build() {
      return new OsgiRuntime(this);
    }
  }

  /** Boot the framework, install+start pax-logging + felix.scr (if requested) and the bundles. */
  public OsgiRuntime boot() throws IOException {
    final Set<String> exports = new LinkedHashSet<>(explicitSystemPackages);
    // A package exported by an installed bundle has that bundle as its sole provider inside the
    // framework, so it must NEVER be system-exported: bnd emits substitutable exports (an exported
    // package also appears in Import-Package), and re-exporting it from the system bundle would
    // split the class — a NoClassDefFoundError at SCR injection time.
    final Set<String> bundleExportedPackages = new LinkedHashSet<>();
    for (Path bundleJar : bundleJars) {
      bundleExportedPackages.addAll(
          packageNames(readManifestHeader(bundleJar, Constants.EXPORT_PACKAGE)));
    }
    for (String name : embeddedBundleNames) {
      try (InputStream in = openEmbedded(name)) {
        bundleExportedPackages.addAll(
            packageNames(readManifestHeader(in, Constants.EXPORT_PACKAGE)));
      }
    }
    for (Path bundleJar : bundleJars) {
      exports.addAll(
          mirrorImportsAsExports(readManifestHeader(bundleJar, Constants.IMPORT_PACKAGE)));
    }
    // Embedded bundles live as classpath resources, not files: read each one's manifest straight
    // from its jar stream for the same Import-Package mirror — no extraction to a temp file.
    for (String name : embeddedBundleNames) {
      try (InputStream in = openEmbedded(name)) {
        exports.addAll(mirrorImportsAsExports(readManifestHeader(in, Constants.IMPORT_PACKAGE)));
      }
    }
    // A mirrored import wires to the system bundle, which exports off the HOST's flat classpath. So
    // keep an export only when it is genuinely host-provided: drop any package an installed bundle
    // exports (it owns it internally), and fail fast on any remaining package the host classloader
    // cannot resolve — a real missing dependency, surfaced here rather than as an opaque
    // NoClassDefFoundError once SCR tries to inject it.
    exports.removeIf(e -> bundleExportedPackages.contains(packageName(e)));
    final List<String> unresolved =
        exports.stream().map(OsgiRuntime::packageName).filter(p -> !hostResolves(p)).toList();
    if (!unresolved.isEmpty()) {
      throw new IllegalStateException(
          "system.packages.extra would export packages absent from the host classpath: "
              + unresolved);
    }
    if (!paxLoggingJars.isEmpty()) {
      // pax-logging-api provides org.slf4j to bundles; a second provider (the system bundle
      // re-exporting it off the flat classpath) would split the slf4j binder — the R1 scar. Drop
      // org.slf4j from the derived exports so pax is the sole provider inside the framework.
      exports.removeIf(e -> e.equals("org.slf4j") || e.startsWith("org.slf4j;"));
    }

    final FrameworkFactory factory =
        ServiceLoader.load(FrameworkFactory.class)
            .findFirst()
            .orElseThrow(
                () -> new IllegalStateException("no OSGi FrameworkFactory on the classpath"));

    final Path storage;
    try {
      storage = Files.createTempDirectory("osgi-runtime-felix");
    } catch (IOException ex) {
      throw new IOException("failed to create Felix storage dir", ex);
    }
    final Map<String, String> config = new java.util.HashMap<>();
    config.put(Constants.FRAMEWORK_STORAGE, storage.toString());
    config.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
    // The framework climbs to this level when started, activating each layer in turn (§4.3).
    config.put(Constants.FRAMEWORK_BEGINNING_STARTLEVEL, Integer.toString(START_LEVEL_BUNDLES));
    if (!exports.isEmpty()) {
      config.put(Constants.FRAMEWORK_SYSTEMPACKAGES_EXTRA, String.join(",", exports));
    }
    if (!paxLoggingJars.isEmpty()) {
      // pax-logging-logback reuses the host's logback LoggerContext (one context, host-owned)
      // rather than a private one; and drains framework/bundle/service events at WARN into the
      // LogService.
      config.put("org.ops4j.pax.logging.StaticLogbackContext", "true");
      config.put("org.ops4j.pax.logging.service.frameworkEventsLogLevel", "WARN");
    }

    framework = factory.newFramework(config);
    try {
      framework.init();

      // Install everything at level 0, each bundle pinned to its layer and marked
      // persistently-started; the framework's native start-level machinery — not a hand-ordered
      // loop — then drives activation in level order when we raise its level below.
      // Classpath-located
      // jars install by location URL; embedded ones stream straight into Felix's own bundle cache
      // (installBundle copies the bytes), so neither needs a temp file.
      for (Path paxJar : paxLoggingJars) {
        installAtLevel(paxJar.toUri().toString(), START_LEVEL_LOGGING);
      }
      for (String name : embeddedPaxLoggingNames) {
        installEmbeddedAtLevel(name, START_LEVEL_LOGGING);
      }
      for (String runtimeJar : runtimeJars) {
        installAtLevel("file:" + runtimeJar, START_LEVEL_FRAMEWORK_RUNTIME);
      }
      for (String name : embeddedRuntimeNames) {
        installEmbeddedAtLevel(name, START_LEVEL_FRAMEWORK_RUNTIME);
      }
      int bundleCount = 0;
      for (Path bundleJar : bundleJars) {
        installAtLevel(bundleLocationUrl(bundleJar), START_LEVEL_BUNDLES);
        bundleCount++;
      }
      for (String name : embeddedBundleNames) {
        installEmbeddedAtLevel(name, START_LEVEL_BUNDLES);
        bundleCount++;
      }

      // Raise the framework to its beginning level; STARTED fires once that level is reached and
      // every eligible bundle has been activated in start-level order.
      final CountDownLatch started = new CountDownLatch(1);
      framework
          .getBundleContext()
          .addFrameworkListener(
              event -> {
                if (event.getType() == org.osgi.framework.FrameworkEvent.STARTED) {
                  started.countDown();
                }
              });
      framework.start();
      if (!started.await(30, TimeUnit.SECONDS)) {
        throw new IOException("OSGi framework did not reach start level " + START_LEVEL_BUNDLES);
      }

      if (startScr
          && awaitServiceByName("org.osgi.service.component.runtime.ServiceComponentRuntime", 5000)
              == null) {
        throw new IllegalStateException(
            "felix.scr reached its start level but ServiceComponentRuntime never appeared");
      }
      LOG.info("OSGi runtime booted: {} bundle(s) installed and started", bundleCount);
    } catch (BundleException ex) {
      throw new IOException("failed to boot OSGi runtime", ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted booting OSGi runtime", ex);
    }
    return this;
  }

  /**
   * Install a bundle, pin it to {@code startLevel}, and mark it persistently-started. While the
   * framework's current level is below {@code startLevel} the bundle stays {@code INSTALLED}; the
   * framework activates it when it climbs to that level — no manual {@code .start()} ordering.
   */
  private void installAtLevel(String location, int startLevel) throws BundleException {
    final Bundle bundle = context().installBundle(location);
    bundle.adapt(BundleStartLevel.class).setStartLevel(startLevel);
    bundle.start();
  }

  /**
   * Install an embedded jar ({@code /META-INF/bundles/<name>}) by streaming its bytes straight into
   * Felix's bundle cache — {@code installBundle(location, stream)} copies them — then pin it to its
   * layer. No temp file: Felix owns the persisted copy. The location string is informational (the
   * resource path), used only as the bundle's identity in the cache and logs.
   */
  private void installEmbeddedAtLevel(String name, int startLevel)
      throws BundleException, IOException {
    try (InputStream in = openEmbedded(name)) {
      final Bundle bundle = context().installBundle(EMBEDDED_BUNDLES_ROOT + name, in);
      bundle.adapt(BundleStartLevel.class).setStartLevel(startLevel);
      bundle.start();
    }
  }

  /** Open {@code /META-INF/bundles/<name>} from the running jar, or fail loudly if it is absent. */
  private InputStream openEmbedded(String name) throws IOException {
    final String resource = EMBEDDED_BUNDLES_ROOT + name;
    final InputStream in = OsgiRuntime.class.getResourceAsStream(resource);
    if (in == null) {
      throw new IOException("embedded bundle not found on classpath: " + resource);
    }
    return in;
  }

  /**
   * The booted framework's bundle context, for the host seam to read services from the registry.
   */
  public BundleContext context() {
    if (framework == null) {
      throw new IllegalStateException("OSGi runtime not booted");
    }
    return framework.getBundleContext();
  }

  /**
   * Resolve a single service of {@code type} from the registry, waiting up to {@code timeoutMillis}
   * for SCR to publish it (a component's service appears only after its mandatory references bind).
   */
  public <T> T awaitService(Class<T> type, long timeoutMillis) {
    final ServiceTracker<T, T> tracker = new ServiceTracker<>(context(), type, null);
    tracker.open();
    try {
      return tracker.waitForService(timeoutMillis);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted awaiting service " + type.getName(), ex);
    } finally {
      tracker.close();
    }
  }

  @Override
  public void close() {
    if (framework != null) {
      try {
        framework.stop();
        framework.waitForStop(5000);
      } catch (org.osgi.framework.BundleException | InterruptedException ex) {
        if (ex instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        LOG.warn("OSGi runtime shutdown was not clean", ex);
      }
    }
  }

  private Object awaitServiceByName(String className, long timeoutMillis) {
    final ServiceTracker<Object, Object> tracker = new ServiceTracker<>(context(), className, null);
    tracker.open();
    try {
      return tracker.waitForService(timeoutMillis);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return null;
    } finally {
      tracker.close();
    }
  }

  /**
   * A directory-based bundle (an exploded {@code target/classes} during reactor builds) installs
   * via the {@code reference:} scheme; a packaged jar (the embedded-intact bundle in a deployed
   * exec-jar) installs by its plain file URI. Serving both lets one runtime cover the reactor test
   * and the deployed process unchanged.
   */
  private static String bundleLocationUrl(Path bundleJar) {
    return Files.isDirectory(bundleJar)
        ? "reference:" + bundleJar.toUri()
        : bundleJar.toUri().toString();
  }

  /**
   * Turn a bundle's {@code Import-Package} header into system-bundle export clauses: package name
   * kept, an explicit version range narrowed to its lower bound (so the importer's range is
   * satisfied), every other directive/attribute dropped. The bundle's OWN exports are not mirrored
   * — only what it imports needs wiring for it to resolve against the host's flat classpath.
   */
  private static Set<String> mirrorImportsAsExports(String importPackage) {
    if (importPackage == null || importPackage.isBlank()) {
      return Set.of();
    }
    final Set<String> exports = new LinkedHashSet<>();
    for (String clause : splitClauses(importPackage)) {
      exports.add(importClauseToExport(clause));
    }
    return exports;
  }

  /**
   * Bare package name of an export/import clause ({@code foo.bar;version=1.0} → {@code foo.bar}).
   */
  private static String packageName(String clause) {
    return clause.split(";", 2)[0].trim();
  }

  /**
   * Whether the host (flat) classpath actually carries {@code packageName}. OsgiRuntime runs in the
   * host world, so its own classloader is the flat classpath the system bundle exports from; a
   * package with no directory resource there cannot be wired into the framework.
   */
  private static boolean hostResolves(String packageName) {
    final String path = packageName.replace('.', '/');
    try {
      return OsgiRuntime.class.getClassLoader().getResources(path).hasMoreElements();
    } catch (IOException ex) {
      return false;
    }
  }

  /** All package names in a {@code Export-Package}/{@code Import-Package} header. */
  private static Set<String> packageNames(String header) {
    if (header == null || header.isBlank()) {
      return Set.of();
    }
    final Set<String> names = new LinkedHashSet<>();
    for (String clause : splitClauses(header)) {
      names.add(packageName(clause));
    }
    return names;
  }

  private static String importClauseToExport(String clause) {
    final String pkg = clause.split(";", 2)[0].trim();
    for (String part : clause.split(";")) {
      final String p = part.trim();
      if (p.startsWith("version=")) {
        final String raw = p.substring("version=".length()).replace("\"", "");
        final String lower =
            raw.startsWith("[") || raw.startsWith("(") ? raw.substring(1).split(",")[0] : raw;
        return pkg + ";version=" + lower.trim();
      }
    }
    return pkg;
  }

  private static List<String> splitClauses(String header) {
    final List<String> clauses = new ArrayList<>();
    int depth = 0;
    int start = 0;
    for (int i = 0; i < header.length(); i++) {
      final char c = header.charAt(i);
      if (c == '"') {
        depth ^= 1;
      } else if (c == ',' && depth == 0) {
        clauses.add(header.substring(start, i));
        start = i + 1;
      }
    }
    clauses.add(header.substring(start));
    return clauses;
  }

  private static String readManifestHeader(Path bundleJar, String header) throws IOException {
    if (Files.isDirectory(bundleJar)) {
      try (InputStream in = Files.newInputStream(bundleJar.resolve("META-INF/MANIFEST.MF"))) {
        return new Manifest(in).getMainAttributes().getValue(header);
      }
    }
    try (JarFile jar = new JarFile(bundleJar.toFile())) {
      final Manifest manifest = jar.getManifest();
      return manifest == null ? null : manifest.getMainAttributes().getValue(header);
    }
  }

  /** Read {@code header} from a jar STREAM's manifest (an embedded bundle resource). */
  private static String readManifestHeader(InputStream jarStream, String header)
      throws IOException {
    try (JarInputStream jar = new JarInputStream(jarStream)) {
      final Manifest manifest = jar.getManifest();
      return manifest == null ? null : manifest.getMainAttributes().getValue(header);
    }
  }
}
