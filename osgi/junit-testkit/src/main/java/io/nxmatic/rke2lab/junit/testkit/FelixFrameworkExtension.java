package io.nxmatic.rke2lab.junit.testkit;

import io.nxmatic.rke2lab.osgi.bnd.EmbedCapability;
import io.nxmatic.rke2lab.osgi.boot.discovery.BundleIndex;
import io.nxmatic.rke2lab.osgi.boot.discovery.BundleLocation;
import io.nxmatic.rke2lab.osgi.boot.discovery.BundleManifest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

  private static final Logger LOG = LoggerFactory.getLogger(FelixFrameworkExtension.class);

  private final String systemPackagesExtra;
  private final String bootDelegation;
  private final boolean startScr;
  private final List<String> classpathBundles;
  private final List<String> reactorBundles;
  private final List<String> matchFilters;

  private final Map<String, Bundle> installedBundles = new LinkedHashMap<>();
  private Framework framework;

  /**
   * The shared boot-discovery index over the test classpath — the source-of-truth the prod executor
   * reads too.
   */
  private final BundleIndex classpath = BundleIndex.ofClasspath();

  private FelixFrameworkExtension(Builder builder) {
    Set<String> exports = new LinkedHashSet<>(builder.systemPackages);
    for (String symbolicName : builder.exportImportsOf) {
      exports.addAll(classpath.exportsForImportsOf(symbolicName));
    }
    this.systemPackagesExtra = exports.isEmpty() ? null : String.join(",", exports);
    this.bootDelegation =
        builder.bootDelegation.isEmpty() ? null : String.join(",", builder.bootDelegation);
    this.startScr = builder.startScr;
    this.classpathBundles = List.copyOf(builder.classpathBundles);
    this.reactorBundles = List.copyOf(builder.reactorBundles);
    this.matchFilters = List.copyOf(builder.matchFilters);
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Declares the framework topology installed+started in {@code beforeAll}. */
  public static final class Builder {
    private final List<String> systemPackages = new ArrayList<>();
    private final List<String> bootDelegation = new ArrayList<>();
    private boolean startScr;
    private final List<String> classpathBundles = new ArrayList<>();
    private final List<String> reactorBundles = new ArrayList<>();
    private final List<String> exportImportsOf = new ArrayList<>();
    private final List<String> matchFilters = new ArrayList<>();

    /** Export these packages from the system bundle (value of {@code system.packages.extra}). */
    public Builder systemPackages(String... packages) {
      this.systemPackages.addAll(Arrays.asList(packages));
      return this;
    }

    /**
     * Boot-delegate these packages ({@code org.osgi.framework.bootdelegation}): every bundle loads
     * them from the parent (app) classloader, bypassing import/export wiring. The mechanism for
     * JDK-internal packages a library reaches reflectively without importing them — notably {@code
     * sun.misc} for byte-buddy's {@code ClassInjector.UsingReflection} (Unsafe). A system-bundle
     * EXPORT cannot serve these: the consumer never declares the import to wire to.
     */
    public Builder bootDelegation(String... packages) {
      this.bootDelegation.addAll(Arrays.asList(packages));
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
     * Install+start felix.scr before the reactor bundles. The DS-runtime API it imports
     * (org.osgi.service.component / util.promise / util.function) is no longer system-exported: the
     * staged spec-jar bundles provide it, so felix.scr wires to them bundle-to-bundle.
     */
    public Builder withScr() {
      this.startScr = true;
      return this;
    }

    /**
     * Third-party jars installed+started, in order, each located by its {@code Bundle-SymbolicName}
     * — the identity it declares, NOT its Maven file name (so {@code
     * org.ops4j.pax.logging.pax-logging-api}, not {@code pax-logging-api}). For the boot stack and
     * library bundles we do not own and cannot mark with an embed capability.
     */
    public Builder installFromClasspath(String... symbolicNames) {
      this.classpathBundles.addAll(Arrays.asList(symbolicNames));
      return this;
    }

    /**
     * Bundles installed+started, in order, each located by its {@code Bundle-SymbolicName}; fetch
     * via {@link #bundle}. For a proof that NAMES the specific host bundle(s) it installs (e.g. a
     * doctor-core host). To select OUR embeddable fixtures by what they declare, use {@link
     * #installMatching(String)}; for a third-party jar by identity, {@link
     * #installFromClasspath(String...)}.
     */
    public Builder installBundles(String... symbolicNames) {
      this.reactorBundles.addAll(Arrays.asList(symbolicNames));
      return this;
    }

    /**
     * Install+start every embeddable bundle whose {@link EmbedCapability embed capability} matches
     * {@code ldapFilter} — selection by what each bundle DECLARES, never a name a test keeps in
     * sync. {@code (type=model)} boots the deployed exec-jar's model set; {@code
     * (&(type=fixture)(suite=scr)(role=consumer))} installs ONE fixture and, by omitting {@code
     * role=provider}, is how the anti-cheat proves a consumer stays unsatisfied. Each installed
     * bundle is fetchable via {@link #bundle} under its {@code Bundle-SymbolicName}.
     */
    public Builder installMatching(String ldapFilter) {
      this.matchFilters.add(ldapFilter);
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
    // Off (Felix defaults it true): no stack-inspection guesswork falling a non-wired class through
    // to the parent (app) classloader. Every load must be satisfied by a bundle's imports /
    // Bundle-ClassPath / the system bundle, or fail loudly — deterministic, and a seam package can
    // never be served by the flat parent instead of its single declared exporter. Mirrors the prod
    // FrameworkLauncher.
    config.put("felix.bootdelegation.implicit", "false");
    // The test class may opt into louder framework diagnostics via @FrameworkLog — the only place a
    // failed resolve()/activation explains WHICH requirement could not be wired. Default ERROR.
    context
        .getElement()
        .map(element -> element.getAnnotation(FrameworkLog.class))
        .ifPresent(
            log -> config.put("felix.log.level", Integer.toString(log.value().felixLevel())));
    if (systemPackagesExtra != null) {
      config.put(Constants.FRAMEWORK_SYSTEMPACKAGES_EXTRA, systemPackagesExtra);
    }
    if (bootDelegation != null) {
      config.put(Constants.FRAMEWORK_BOOTDELEGATION, bootDelegation);
    }
    framework = factory.newFramework(config);
    framework.init();
    framework.start();

    if (startScr) {
      startScr();
    }
    for (String symbolicName : classpathBundles) {
      installFromClasspath(symbolicName).start();
    }
    for (String symbolicName : reactorBundles) {
      Bundle bundle = install(symbolicName);
      bundle.start();
      installedBundles.put(symbolicName, bundle);
    }
    // Bundles selected by what they DECLARE (the embed capability) rather than named. Keyed in the
    // installed map by the bundle's OWN Bundle-SymbolicName — they were discovered by capability,
    // never named by the caller, so OSGi's native identity is the only honest key.
    for (String ldapFilter : matchFilters) {
      for (BundleLocation location : classpath.matching(ldapFilter)) {
        Bundle bundle = installAt(location);
        bundle.start();
        installedBundles.put(bundle.getSymbolicName(), bundle);
      }
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

  /**
   * The bundle the builder installed under {@code symbolicName}, for tests that need it. Bundles
   * installed by {@link Builder#installMatching(String) capability filter} are keyed by their own
   * {@code Bundle-SymbolicName} too.
   */
  public Bundle bundle(String symbolicName) {
    Bundle bundle = installedBundles.get(symbolicName);
    if (bundle == null) {
      throw new IllegalArgumentException("no bundle installed for " + symbolicName);
    }
    return bundle;
  }

  /**
   * Install the bundle declaring {@code symbolicName} as its {@code Bundle-SymbolicName}, located
   * on the test classpath via {@link BundleIndex#locateBySymbolicName(String)} — by the identity
   * the bundle publishes, not a guess from its Maven file name. The test module depends on the
   * bundle modules as maven dependencies; during reactor {@code -am} builds those resolve to {@code
   * target/classes} directories, which OSGi loads as directory-based bundles when they carry a
   * META-INF/MANIFEST.MF.
   */
  public Bundle install(String symbolicName) throws Exception {
    return installAt(classpath.locateBySymbolicName(symbolicName));
  }

  /**
   * Install — WITHOUT starting — every bundle whose {@link EmbedCapability embed capability}
   * matches {@code ldapFilter}, and return the handles in install order. Selection is by what each
   * bundle DECLARES ({@code (&(type=fixture)(suite=extender))}), never a {@code
   * Bundle-SymbolicName} a test keeps in sync — the imperative, install-only counterpart of {@link
   * Builder#installMatching} (the declared topology, which also starts). For a proof that drives
   * resolution or activation BY HAND over the returned handles: the extender resolve/refuse proof,
   * or a host the test starts itself. Each handle is also fetchable later via {@link #bundle} under
   * its own symbolic name.
   */
  public List<Bundle> installMatching(String ldapFilter) throws Exception {
    final List<Bundle> installed = new ArrayList<>();
    for (BundleLocation location : classpath.matching(ldapFilter)) {
      Bundle bundle = installAt(location);
      installedBundles.put(bundle.getSymbolicName(), bundle);
      installed.add(bundle);
    }
    return installed;
  }

  /**
   * A host bundle and the {@code -test} fragment attached to it, both installed, neither started.
   */
  public record FixtureWithHost(Bundle host, Bundle fragment) {}

  /**
   * Install a fixture {@code -test} FRAGMENT selected by {@code ldapFilter} together with the host
   * it attaches to — neither started — and return both handles. The fragment is OURS (it declares
   * the embed capability {@code ldapFilter} matches); its host is the prod bundle it names in
   * {@code Fragment-Host}, located by THAT declared symbolic name. So the test names neither: it
   * selects the fragment by what it declares, and the fragment declares its own host. The caller
   * then resolves the host (attaching the fragment, OSGi Core §3.14) and starts it.
   *
   * <p>A fragment has no lifecycle of its own — it is installed but never started; resolving the
   * host merges it in. {@code ldapFilter} must match exactly one fragment.
   */
  public FixtureWithHost installFixtureWithHost(String ldapFilter) throws Exception {
    final List<BundleLocation> matched = classpath.matching(ldapFilter);
    if (matched.size() != 1) {
      throw new IllegalArgumentException(
          "expected exactly one fixture fragment matching "
              + ldapFilter
              + ", found "
              + matched.size());
    }
    final BundleLocation fragmentLocation = matched.get(0);
    final String hostBsn = BundleManifest.from(fragmentLocation).fragmentHost();
    if (hostBsn == null) {
      throw new IllegalArgumentException(
          "fixture matching " + ldapFilter + " declares no Fragment-Host — not a -test fragment");
    }
    final Bundle host = install(hostBsn);
    final Bundle fragment = installAt(fragmentLocation);
    installedBundles.put(host.getSymbolicName(), host);
    installedBundles.put(fragment.getSymbolicName(), fragment);
    return new FixtureWithHost(host, fragment);
  }

  /**
   * Install the bundle at a {@link BundleLocation} — a classpath jar/dir by its URL, a staged jar
   * by streaming its bytes into the framework's cache. One install path for both sources.
   */
  private Bundle installAt(BundleLocation location) throws Exception {
    return switch (location) {
      case BundleLocation.Staged staged -> {
        try (var in = staged.open()) {
          yield context().installBundle(staged.locationId(), in);
        }
      }
      case BundleLocation.OnClasspath onClasspath ->
          context().installBundle(onClasspath.locationId());
    };
  }

  /**
   * Resolve {@code bundles} against the framework wiring. Returns whether ALL resolved — but a bare
   * {@code false} is the blindness this testkit exists to avoid, so on failure it first LOGS, per
   * still-unresolved bundle, the requirements OSGi could not wire (the same "which constraint
   * failed" a raw {@code resolveBundles} swallows). A caller that fails the test on {@code false}
   * then has the reason in the test log, not just a boolean. (Felix's own {@code felix.log.level} —
   * raised via {@link FrameworkLog} — covers the resolver's internal trace; this covers the
   * post-mortem.)
   */
  public boolean resolve(List<Bundle> bundles) {
    final boolean resolved =
        framework.adapt(org.osgi.framework.wiring.FrameworkWiring.class).resolveBundles(bundles);
    if (!resolved) {
      for (Bundle bundle : bundles) {
        if ((bundle.getState() & Bundle.RESOLVED) == 0) {
          LOG.error(
              "bundle {} [{}] did not resolve; unsatisfied requirements: {}",
              bundle.getSymbolicName(),
              bundle.getBundleId(),
              unsatisfiedRequirements(bundle));
        }
      }
    }
    return resolved;
  }

  /**
   * The requirements of {@code bundle} that no installed bundle's capability satisfies — the
   * actionable part of a resolution failure (a missing {@code Import-Package}, an unattachable
   * fragment host, an {@code osgi.ee} mismatch). Computed by diffing the bundle's declared
   * requirements against the framework's resolved capabilities, so it names exactly what to
   * install.
   */
  private List<String> unsatisfiedRequirements(Bundle bundle) {
    final var wiring = framework.adapt(org.osgi.framework.wiring.FrameworkWiring.class);
    final List<String> unsatisfied = new ArrayList<>();
    for (var requirement :
        bundle
            .adapt(org.osgi.framework.wiring.BundleRevision.class)
            .getDeclaredRequirements(null)) {
      final var candidates = wiring.findProviders(requirement);
      if (candidates.isEmpty()) {
        unsatisfied.add(requirement.toString());
      }
    }
    return unsatisfied;
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
   * Locate a third-party jar (e.g. {@code org.apache.felix.scr}) on the test classpath by its
   * {@code Bundle-SymbolicName}, via {@link BundleIndex#locateBySymbolicName(String)} — by the
   * identity the bundle declares, never a guess from its Maven file name.
   */
  private Bundle installFromClasspath(String symbolicName) throws Exception {
    return installAt(classpath.locateBySymbolicName(symbolicName));
  }

  /**
   * Install+start felix.scr, first pulling in the passive jars it imports but the system bundle
   * does not export — the DS-API trio ({@code org.osgi.service.component} / {@code util.promise} /
   * {@code util.function}). Those packages are felix.scr-internal: wired bundle-to-bundle, never
   * system-exported (no {@code SCR_API_PACKAGES} shim), so felix.scr would stay INSTALLED without
   * them. The walk is the SHARED {@link BundleIndex#closeOverImports} the prod {@code BootPlanner}
   * also drives — one frame, seeded here with felix.scr and nothing pre-provided (this testkit does
   * not system-export the trio); the two executors differ ONLY in the per-jar action they
   * CONTRIBUTE. Here it INSTALLS each pulled jar into the framework (the planner adds an
   * Installable instead).
   */
  private void startScr() throws Exception {
    final BundleLocation scrLocation = classpath.locateBySymbolicName("org.apache.felix.scr");
    // The shared closure takes a plain Consumer (its boot-discovery API stays pure, no checked
    // throws); installAt throws checked BundleException/IOException, so the contributed handler
    // sneaky-rethrows it — the exception surfaces intact from startScr (which declares throws), no
    // wrapping, no exception type leaking into the pure API.
    classpath.closeOverImports(
        List.of(scrLocation),
        Set.of(),
        passive -> installPassiveSneaky(passive)); // installed so the importer wires to it
    final Bundle scr = installAt(scrLocation);
    scr.start();
    if (scr.getState() != Bundle.ACTIVE) {
      throw new IllegalStateException("felix.scr did not reach ACTIVE — DS is not running");
    }
  }

  /**
   * {@link #installAt} as a plain {@link java.util.function.Consumer} body — see {@link #sneaky}.
   */
  private void installPassiveSneaky(BundleLocation location) {
    try {
      installAt(location);
    } catch (Exception ex) {
      sneaky(ex);
    }
  }

  /**
   * Sneaky-throw: rethrow a checked exception without declaring it, so a checked-throwing body can
   * be passed where a non-throwing {@code Consumer} is expected. The cast is erased at runtime, so
   * the original exception propagates unchanged to the nearest {@code throws} (here {@code
   * startScr}).
   */
  @SuppressWarnings("unchecked")
  private static <E extends Throwable> void sneaky(Throwable ex) throws E {
    throw (E) ex;
  }
}
