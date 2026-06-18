package io.nxmatic.rke2lab.osgibench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
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
 * Boots a real embedded Felix framework once per test class so bench tests observe the actual OSGi
 * resolution/runtime — not the hand-rolled resolver algorithm. A plain Jupiter extension so the
 * tests stay ordinary JUnit5 and launch from VSCode Test Explorer as well as surefire.
 *
 * <p>Built through {@link #builder()}, which DECLARES the framework topology — exported API
 * packages, whether SCR runs, which runtime jars and bench bundles to install+start — so the test
 * body is left with only the PROOF ({@link #awaitService}, {@link #resolve}). The declaration is
 * where the anti-cheat reads: a test that omits {@code .installBundles("scr-provider")} is visibly
 * proving the consumer stays unsatisfied.
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
  private final List<String> benchBundles;

  private final Map<String, Bundle> installedBenchBundles = new LinkedHashMap<>();
  private Framework framework;

  private FelixFrameworkExtension(Builder builder) {
    this.systemPackagesExtra =
        builder.systemPackages.isEmpty() ? null : String.join(",", builder.systemPackages);
    this.startScr = builder.startScr;
    this.classpathBundles = List.copyOf(builder.classpathBundles);
    this.benchBundles = List.copyOf(builder.benchBundles);
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Declares the framework topology installed+started in {@code beforeAll}. */
  public static final class Builder {
    private final List<String> systemPackages = new ArrayList<>();
    private boolean startScr;
    private final List<String> classpathBundles = new ArrayList<>();
    private final List<String> benchBundles = new ArrayList<>();

    /** Export these packages from the system bundle (value of {@code system.packages.extra}). */
    public Builder systemPackages(String... packages) {
      this.systemPackages.addAll(Arrays.asList(packages));
      return this;
    }

    /** Install+start felix.scr before the bench bundles and export the DS-runtime API it needs. */
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

    /** Bench bundles (by classifier) installed+started, in order; fetch via {@link #bundle}. */
    public Builder installBundles(String... classifiers) {
      this.benchBundles.addAll(Arrays.asList(classifiers));
      return this;
    }

    public FelixFrameworkExtension build() {
      return new FelixFrameworkExtension(this);
    }
  }

  @Override
  public void beforeAll(ExtensionContext context) throws Exception {
    Path storage = Files.createTempDirectory("osgi-bench-felix");
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
    for (String classifier : benchBundles) {
      Bundle bundle = install(classifier);
      bundle.start();
      installedBenchBundles.put(classifier, bundle);
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

  /** The bench bundle the builder installed under {@code classifier}, for tests that need it. */
  public Bundle bundle(String classifier) {
    Bundle bundle = installedBenchBundles.get(classifier);
    if (bundle == null) {
      throw new IllegalArgumentException("no bench bundle installed for classifier " + classifier);
    }
    return bundle;
  }

  /**
   * Install the bench bundle whose artifact ID contains {@code osgi-bench-<classifier>}. Locates
   * the bundle on the test classpath (java.class.path), not from a target/ directory, because this
   * test module depends on the sibling bundle modules as maven dependencies. During reactor builds
   * with {@code -am}, dependencies resolve to {@code target/classes} directories (not jars), which
   * OSGi can load as directory-based bundles if they contain META-INF/MANIFEST.MF.
   */
  public Bundle install(String classifier) throws Exception {
    String classpathProperty = System.getProperty("java.class.path");
    Path bundleLocation =
        Arrays.stream(classpathProperty.split(System.getProperty("path.separator")))
            .map(Paths::get)
            .filter(
                p ->
                    p.toString().contains("osgi-bench-" + classifier)
                        && (Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar")
                            || Files.isDirectory(p)
                                && Files.exists(p.resolve("META-INF/MANIFEST.MF"))))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "no osgi-bench-"
                            + classifier
                            + " bundle (jar or classes dir) on classpath"));
    String bundleUrl =
        Files.isDirectory(bundleLocation)
            ? "reference:" + bundleLocation.toUri().toString()
            : bundleLocation.toUri().toString();
    return context().installBundle(bundleUrl);
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
