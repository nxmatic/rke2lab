package io.nxmatic.rke2lab.osgi.testkit;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;
import org.osgi.util.tracker.ServiceTracker;

/**
 * Boots a real embedded Felix framework once per test class so OSGi proofs observe the actual OSGi
 * resolution/runtime — not a hand-rolled resolver algorithm. A plain Jupiter extension so the tests
 * stay ordinary JUnit5 and launch from VSCode Test Explorer as well as surefire.
 *
 * <p>Built through {@link #builder()}, which DECLARES the framework topology — exported API
 * packages, whether SCR runs, which runtime jars and reactor bundles to install+start — so the test
 * body is left with only the PROOF ({@link #awaitService}, {@link #resolve}). The declaration is
 * where the anti-cheat reads: a test that omits the provider bundle is visibly proving the consumer
 * stays unsatisfied.
 *
 * <p>{@code systemPackages(...)} exports an API package from the system bundle (= the test's app
 * classloader). A bundle that imports that package then shares the SAME class as the test, so a
 * service it registers is castable to the test's type — TYPED access, no reflection, no {@code
 * ClassCastException} across the bundle/app boundary. Export it WITH the version the importer asks
 * for, and from ONE place only: a second (unversioned) exporter wires importers to a different
 * class copy and the typed lookup silently misses.
 */
public final class FelixFrameworkExtension implements BeforeAllCallback, AfterAllCallback {

  /**
   * The DS-runtime API packages felix.scr imports as MANDATORY but the {@code osgi.core} system
   * bundle does not carry. {@link Builder#withScr()} exports them automatically; the matching jars
   * must be on the test classpath (the system bundle loads them).
   */
  public static final String SCR_API_PACKAGES =
      "org.osgi.service.component;version=1.5,"
          + "org.osgi.service.component.runtime;version=1.5,"
          + "org.osgi.service.component.runtime.dto;version=1.5,"
          + "org.osgi.util.promise;version=1.3,"
          + "org.osgi.util.function;version=1.2";

  private final String systemPackagesExtra;
  private final boolean startScr;
  private final List<String> classpathBundles;
  private final List<String> reactorBundles;

  private final Map<String, Bundle> installedBundles = new LinkedHashMap<>();
  private Framework framework;

  private FelixFrameworkExtension(Builder builder) {
    Set<String> exports = new LinkedHashSet<>(builder.systemPackages);
    for (String artifact : builder.exportImportsOf) {
      exports.addAll(mirrorImportsAsExports(artifact));
    }
    this.systemPackagesExtra = exports.isEmpty() ? null : String.join(",", exports);
    this.startScr = builder.startScr;
    this.classpathBundles = List.copyOf(builder.classpathBundles);
    this.reactorBundles = List.copyOf(builder.reactorBundles);
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Declares the framework topology installed+started in {@code beforeAll}. */
  public static final class Builder {
    private final List<String> systemPackages = new ArrayList<>();
    private boolean startScr;
    private final List<String> classpathBundles = new ArrayList<>();
    private final List<String> reactorBundles = new ArrayList<>();
    private final List<String> exportImportsOf = new ArrayList<>();

    /** Export these packages from the system bundle (value of {@code system.packages.extra}). */
    public Builder systemPackages(String... packages) {
      this.systemPackages.addAll(Arrays.asList(packages));
      return this;
    }

    /**
     * Export, from the system bundle, exactly the packages each {@code artifact} bundle IMPORTS —
     * read from its own bnd-computed {@code Import-Package} manifest header. This is the fail-fast
     * alternative to a hand-maintained {@link #systemPackages} list for a heavy bundle: the set is
     * always in sync with what bnd actually computed (no stale versions, no typos), and a genuinely
     * missing artifact fails at {@code build()} by name rather than as an opaque resolver timeout.
     * Use when the proof only needs the {@code artifact} bundle to RESOLVE, not its siblings to be
     * installed.
     */
    public Builder exportImportsOf(String... artifacts) {
      this.exportImportsOf.addAll(Arrays.asList(artifacts));
      return this;
    }

    /**
     * Install+start felix.scr before the reactor bundles and export the DS-runtime API it needs.
     */
    public Builder withScr() {
      this.startScr = true;
      this.systemPackages.add(SCR_API_PACKAGES);
      return this;
    }

    /** Runtime jars (e.g. {@code org.apache.felix.metatype}) installed+started, in order. */
    public Builder installFromClasspath(String... artifactIds) {
      this.classpathBundles.addAll(Arrays.asList(artifactIds));
      return this;
    }

    /**
     * Reactor bundles installed+started, in order, each located on the classpath by artifact
     * substring; fetch via {@link #bundle}.
     */
    public Builder installBundles(String... artifacts) {
      this.reactorBundles.addAll(Arrays.asList(artifacts));
      return this;
    }

    public FelixFrameworkExtension build() {
      return new FelixFrameworkExtension(this);
    }
  }

  @Override
  public void beforeAll(ExtensionContext context) throws Exception {
    Path storage = Files.createTempDirectory("osgi-testkit-felix");
    FrameworkFactory factory =
        ServiceLoader.load(FrameworkFactory.class)
            .findFirst()
            .orElseThrow(
                () -> new IllegalStateException("no OSGi FrameworkFactory on the classpath"));
    Map<String, String> config = new java.util.HashMap<>();
    config.put(Constants.FRAMEWORK_STORAGE, storage.toString());
    config.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
    if (systemPackagesExtra != null) {
      config.put(Constants.FRAMEWORK_SYSTEMPACKAGES_EXTRA, systemPackagesExtra);
    }
    framework = factory.newFramework(config);
    framework.init();
    framework.start();

    if (startScr) {
      startScr();
    }
    for (String artifactId : classpathBundles) {
      installFromClasspath(artifactId).start();
    }
    for (String artifact : reactorBundles) {
      Bundle bundle = install(artifact);
      bundle.start();
      installedBundles.put(artifact, bundle);
    }
  }

  @Override
  public void afterAll(ExtensionContext context) throws Exception {
    if (framework != null) {
      framework.stop();
      framework.waitForStop(5000);
    }
  }

  public BundleContext context() {
    return framework.getBundleContext();
  }

  /** The reactor bundle the builder installed under {@code artifact}, for tests that need it. */
  public Bundle bundle(String artifact) {
    Bundle bundle = installedBundles.get(artifact);
    if (bundle == null) {
      throw new IllegalArgumentException("no bundle installed for artifact " + artifact);
    }
    return bundle;
  }

  /**
   * Install the reactor bundle whose classpath entry contains {@code artifact}. Locates it on the
   * test classpath (java.class.path), not from a target/ directory, because the test module depends
   * on the bundle modules as maven dependencies. During reactor builds with {@code -am}, those
   * resolve to {@code target/classes} directories (not jars), which OSGi loads as directory-based
   * bundles when they carry a META-INF/MANIFEST.MF.
   */
  public Bundle install(String artifact) throws Exception {
    Path bundleLocation = locateBundle(artifact);
    String bundleUrl =
        Files.isDirectory(bundleLocation)
            ? "reference:" + bundleLocation.toUri().toString()
            : bundleLocation.toUri().toString();
    return context().installBundle(bundleUrl);
  }

  /**
   * Locate, on the test classpath ({@code java.class.path}), the jar or {@code target/classes}
   * directory whose path contains {@code artifact} and carries a manifest. Pure classpath
   * inspection — no framework needed — so it is usable BEFORE {@code framework.init()}.
   */
  private static Path locateBundle(String artifact) {
    return Arrays.stream(
            System.getProperty("java.class.path").split(System.getProperty("path.separator")))
        .map(Paths::get)
        .filter(
            p ->
                p.toString().contains(artifact)
                    && (Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar")
                        || Files.isDirectory(p) && Files.exists(p.resolve("META-INF/MANIFEST.MF"))))
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "no " + artifact + " bundle (jar or classes dir) on classpath"));
  }

  /**
   * Read the {@code Import-Package} header from {@code artifact}'s manifest and turn each clause
   * into a system-bundle export clause: the package name kept, an explicit version range narrowed
   * to its lower bound (so the importer's range is satisfied), every other directive/attribute
   * dropped (resolution, uses, etc. are meaningless on a system-bundle export). The bundle's OWN
   * exports are not mirrored — only what it imports needs wiring for it to resolve.
   */
  private static Set<String> mirrorImportsAsExports(String artifact) {
    String importPackage = readManifestHeader(locateBundle(artifact), Constants.IMPORT_PACKAGE);
    if (importPackage == null || importPackage.isBlank()) {
      return Set.of();
    }
    Set<String> exports = new LinkedHashSet<>();
    for (String clause : splitClauses(importPackage)) {
      exports.add(importClauseToExport(clause));
    }
    return exports;
  }

  /** Lower-bound an import clause's version range and strip all other parameters. */
  private static String importClauseToExport(String clause) {
    String pkg = clause.split(";", 2)[0].trim();
    for (String part : clause.split(";")) {
      String p = part.trim();
      if (p.startsWith("version=")) {
        String raw = p.substring("version=".length()).replace("\"", "");
        String lower =
            raw.startsWith("[") || raw.startsWith("(") ? raw.substring(1).split(",")[0] : raw;
        return pkg + ";version=" + lower.trim();
      }
    }
    return pkg;
  }

  /** Split an OSGi header value on commas that are NOT inside a quoted version range. */
  private static List<String> splitClauses(String header) {
    List<String> clauses = new ArrayList<>();
    int depth = 0;
    int start = 0;
    for (int i = 0; i < header.length(); i++) {
      char c = header.charAt(i);
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

  /** Read a single manifest header from a jar file or an exploded {@code target/classes} dir. */
  private static String readManifestHeader(Path bundleLocation, String header) {
    try {
      if (Files.isDirectory(bundleLocation)) {
        try (InputStream in =
            Files.newInputStream(bundleLocation.resolve("META-INF/MANIFEST.MF"))) {
          return new Manifest(in).getMainAttributes().getValue(header);
        }
      }
      try (JarFile jar = new JarFile(bundleLocation.toFile())) {
        Manifest manifest = jar.getManifest();
        return manifest == null ? null : manifest.getMainAttributes().getValue(header);
      }
    } catch (IOException e) {
      throw new IllegalStateException("cannot read manifest of " + bundleLocation, e);
    }
  }

  public boolean resolve(List<Bundle> bundles) {
    return framework.adapt(org.osgi.framework.wiring.FrameworkWiring.class).resolveBundles(bundles);
  }

  /**
   * Wait up to {@code timeoutMillis} for a service of {@code type} via a {@link ServiceTracker} —
   * the framework notifies the tracker on registration, so this blocks on a listener rather than
   * polling. Returns the service once published, or {@code null} on timeout. The non-racy way to
   * observe SCR activation: a component's service appears only AFTER its mandatory
   * {@code @Reference}s are bound. A {@code null} return is itself a result — it is how the
   * anti-cheat asserts a consumer stays unsatisfied while its provider is absent.
   */
  public <T> T awaitService(Class<T> type, long timeoutMillis) throws InterruptedException {
    ServiceTracker<T, T> tracker = new ServiceTracker<>(context(), type, null);
    tracker.open();
    try {
      return tracker.waitForService(timeoutMillis);
    } finally {
      tracker.close();
    }
  }

  /** By-name variant of {@link #awaitService(Class, long)} for services the testkit cannot type. */
  public Object awaitService(String className, long timeoutMillis) throws InterruptedException {
    ServiceTracker<Object, Object> tracker = new ServiceTracker<>(context(), className, null);
    tracker.open();
    try {
      return tracker.waitForService(timeoutMillis);
    } finally {
      tracker.close();
    }
  }

  /**
   * Locate a runtime jar (e.g. {@code org.apache.felix.scr}) on the test classpath by substring.
   */
  private Bundle installFromClasspath(String artifactId) throws Exception {
    String jar =
        Arrays.stream(
                System.getProperty("java.class.path").split(System.getProperty("path.separator")))
            .filter(p -> p.contains(artifactId))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(artifactId + " not on test classpath"));
    return context().installBundle("file:" + jar);
  }

  /** Install+start felix.scr and block until its {@code ServiceComponentRuntime} appears. */
  private void startScr() throws Exception {
    installFromClasspath("org.apache.felix.scr").start();
    if (awaitService("org.osgi.service.component.runtime.ServiceComponentRuntime", 5000) == null) {
      throw new IllegalStateException(
          "felix.scr started but ServiceComponentRuntime never appeared");
    }
  }
}
