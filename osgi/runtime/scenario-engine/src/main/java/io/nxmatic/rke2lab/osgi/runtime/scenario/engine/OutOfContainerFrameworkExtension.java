package io.nxmatic.rke2lab.osgi.runtime.scenario.engine;

import io.nxmatic.rke2lab.osgi.bnd.EmbedCapability;
import io.nxmatic.rke2lab.osgi.boot.discovery.BundleIndex;
import io.nxmatic.rke2lab.osgi.boot.discovery.BundleInstaller;
import io.nxmatic.rke2lab.osgi.boot.discovery.BundleLocation;
import io.nxmatic.rke2lab.osgi.boot.discovery.BundleManifest;
import io.nxmatic.rke2lab.osgi.runtime.framework.LaunchConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Consumer;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.framework.wiring.FrameworkWiring;
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
public final class OutOfContainerFrameworkExtension implements BeforeAllCallback, AfterAllCallback {

  private static final Logger LOG = LoggerFactory.getLogger(OutOfContainerFrameworkExtension.class);

  private final Optional<String> systemPackagesExtra;
  private final Optional<String> bootDelegation;
  private final boolean startScr;
  private final boolean startResolver;
  private final List<String> classpathBundles;
  private final List<String> reactorBundles;
  private final List<String> matchFilters;

  private final Map<String, Bundle> installedBundles = new LinkedHashMap<>();

  /**
   * Set once by {@link #beforeAll}, never renulled — {@code @MonotonicNonNull} (the set-once
   * convention), reached only through {@link #framework()}.
   */
  private @MonotonicNonNull Framework framework;

  /**
   * The booted framework, or an {@link IllegalStateException} if reached before {@link #beforeAll}
   * — a not-yet-booted world is a precondition violation, not a runtime condition a caller can
   * absorb, so it fails loudly rather than dereferencing null.
   */
  private Framework framework() {
    return Objects.requireNonNull(framework, "framework not booted — beforeAll has not run");
  }

  /**
   * The shared boot-discovery index over the test classpath — the source-of-truth the prod executor
   * reads too.
   */
  private final BundleIndex classpath = BundleIndex.ofClasspath();

  private OutOfContainerFrameworkExtension(Builder builder) {
    Set<String> exports = new LinkedHashSet<>(builder.systemPackages);
    // org.slf4j is universal: every domain bundle logs through it, and no slf4j provider is staged
    // in-container, so it is system-exported by default from the test classpath — one place, not
    // repeated per proxy. Only when the test did not already declare its own slf4j export (a second
    // version would split the package): ScenarioTestkit, e.g., exports 2.0.17 itself.
    if (exports.stream().noneMatch(p -> p.startsWith("org.slf4j;") || p.equals("org.slf4j"))) {
      exports.add("org.slf4j;version=2.0.0");
    }
    for (String symbolicName : builder.exportImportsOf) {
      exports.addAll(classpath.exportsForImportsOf(symbolicName));
    }
    this.systemPackagesExtra =
        exports.isEmpty() ? Optional.empty() : Optional.of(String.join(",", exports));
    this.bootDelegation =
        builder.bootDelegation.isEmpty()
            ? Optional.empty()
            : Optional.of(String.join(",", builder.bootDelegation));
    this.startScr = builder.startScr;
    this.startResolver = builder.startResolver;
    this.classpathBundles = List.copyOf(builder.classpathBundles);
    this.reactorBundles = List.copyOf(builder.reactorBundles);
    this.matchFilters = List.copyOf(builder.matchFilters);
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Declares the framework topology installed+started in {@code beforeAll}. */
  public static final class Builder {

    /**
     * The JUnit-Platform runner world an in-container proxy installs via {@link #withJUnitRunner()}
     * — the single source of truth for it, so each proxy names {@code withJUnitRunner()} rather
     * than copying these symbolic names. Order is install+start order: the opentest4j/apiguardian
     * leaves the platform bundles import come first.
     */
    private static final List<String> JUNIT_RUNNER_BUNDLES =
        List.of(
            "org.opentest4j",
            "org.apiguardian.api",
            "junit-platform-commons",
            "junit-platform-engine",
            "junit-platform-launcher",
            "junit-jupiter-api",
            "junit-jupiter-params",
            "junit-jupiter-engine",
            "io.nxmatic.rke2lab.osgi.runtime.junit.launcher");

    private final List<String> systemPackages = new ArrayList<>();
    private final List<String> bootDelegation = new ArrayList<>();
    // SCR runs by default: nearly every test installs @Component-carrying bundles that need the DS
    // extender to activate. The rare test that must prove behaviour WITHOUT the extender present
    // opts out via withoutScr().
    private boolean startScr = true;
    // felix.resolver runs by default too: the manifests + unitrepo synthesis @Components have a
    // mandatory @Reference org.osgi.service.resolver.Resolver, which felix.resolver's
    // Bundle-Activator
    // registers. A test that installs neither opts out via withoutResolver() (a slightly wider TEST
    // classpath carries the jar regardless, from bundle-test-parent — it is simply not started).
    private boolean startResolver = true;
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
     * Install+start felix.scr before the reactor bundles (the DEFAULT — see {@link #startScr}). The
     * DS-runtime API it imports (org.osgi.service.component / util.promise / util.function) is no
     * longer system-exported: the staged spec-jar bundles provide it, so felix.scr wires to them
     * bundle-to-bundle. Kept as an explicit no-op for tests that document the dependency at the
     * call site; new tests need not call it.
     */
    public Builder withScr() {
      this.startScr = true;
      return this;
    }

    /**
     * Opt OUT of the default felix.scr install — for a test that must prove behaviour with the DS
     * extender ABSENT (e.g. that a bundle Requiring osgi.extender=osgi.component stays unresolved,
     * or that a component stays inactive). The exception to the SCR-by-default rule.
     */
    public Builder withoutScr() {
      this.startScr = false;
      return this;
    }

    /**
     * Install+start felix.resolver before the reactor bundles (the DEFAULT — see {@link
     * #startResolver}). Its Bundle-Activator registers the {@code
     * org.osgi.service.resolver.Resolver} service, a mandatory {@code @Reference} of the manifests
     * + unitrepo synthesis {@code @Component}s. The twin of {@link #withScr()}: an explicit no-op
     * documenting the dependency at the call site; new tests need not call it.
     */
    public Builder withResolver() {
      this.startResolver = true;
      return this;
    }

    /**
     * Opt OUT of the default felix.resolver install — for a test that installs no bundle needing
     * the {@code Resolver} service (the four contact scions, which mock their collaborators). The
     * twin of {@link #withoutScr()}.
     */
    public Builder withoutResolver() {
      this.startResolver = false;
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
     * Install+start the JUnit-Platform runner world every in-container PROXY test needs: the
     * launcher + engine + jupiter API the {@code DoctorCoreTests}-style in-framework runner drives,
     * the opentest4j/apiguardian leaves they import, and this {@code junit-testkit} bundle. These
     * are the PROXY's own infrastructure — the bare-JVM test boots a Felix and runs a JUnit
     * launcher INSIDE it — not a dependency of the host under test, so they are not derivable from
     * the host's manifest and stay named here, in ONE place instead of copied per proxy. Located by
     * {@code Bundle-SymbolicName}, like {@link #installFromClasspath}.
     */
    public Builder withJUnitRunner() {
      this.classpathBundles.addAll(JUNIT_RUNNER_BUNDLES);
      // The in-container runner (scenario-engine) loads ClassRealm from boot.discovery
      // unconditionally (JUnitLauncherCore.wiringOf), so its package must resolve. System-exported
      // (host-flat, from the test classpath) rather than installed as a bundle: the launcher runs
      // at
      // the membrane and reads it through the system bundle, like the JUnit-platform packages.
      this.systemPackages.add("io.nxmatic.rke2lab.osgi.boot.discovery");
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

    public OutOfContainerFrameworkExtension build() {
      return new OutOfContainerFrameworkExtension(this);
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
    Map<String, String> config = new HashMap<>();
    config.put(Constants.FRAMEWORK_STORAGE, storage.toString());
    config.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
    // The Felix invariants every boot shares — the SAME source the live FrameworkLauncher uses, so
    // this executor can never drift from it (§ LaunchConfig.applyFrameworkInvariants).
    LaunchConfig.applyFrameworkInvariants(config);
    // The test class may opt into louder framework diagnostics via @FrameworkLog — the only place a
    // failed resolve()/activation explains WHICH requirement could not be wired. Default ERROR.
    context
        .getElement()
        .map(element -> element.getAnnotation(FrameworkLog.class))
        .ifPresent(
            log ->
                config.put(
                    "felix.log.level", Integer.toString(LaunchConfig.felixLevelOf(log.value()))));
    // Extra launch properties a proof mirrors from the prod FrameworkLauncher — the pax-logging
    // knobs a bundle reads from the framework properties at activation — declared per test class
    // via
    // @FrameworkProperty (repeatable), read here like @FrameworkLog above: an annotation, not a
    // builder verb, so it rides the test class even when the extension comes from a shared factory.
    context
        .getElement()
        .ifPresent(
            element -> {
              for (FrameworkProperty property :
                  element.getAnnotationsByType(FrameworkProperty.class)) {
                config.put(property.name(), property.value());
              }
            });
    systemPackagesExtra.ifPresent(
        value -> config.put(Constants.FRAMEWORK_SYSTEMPACKAGES_EXTRA, value));
    bootDelegation.ifPresent(value -> config.put(Constants.FRAMEWORK_BOOTDELEGATION, value));
    framework = factory.newFramework(config);
    framework.init();
    framework.start();

    if (startScr) {
      startScr();
    }
    if (startResolver) {
      startResolver();
    }
    // Install EVERYTHING first, start EVERYTHING after — two passes, never interleaved. OSGi
    // resolution is order-independent (the resolver wires against the whole INSTALLED set), but a
    // bundle's start() forces its resolution at that instant: interleaving install+start made an
    // early starter (scenario-engine, which imports com.tngtech.jgiven.impl) fail because
    // jgiven-wrap
    // — installed in a later group — was not yet present to export it. Separating the phases makes
    // the install order truly irrelevant.
    final List<Bundle> toStart = new ArrayList<>();
    for (String symbolicName : classpathBundles) {
      toStart.add(installFromClasspath(symbolicName));
    }
    for (String symbolicName : reactorBundles) {
      final Bundle bundle = install(symbolicName);
      installedBundles.put(symbolicName, bundle);
      toStart.add(bundle);
    }
    // Bundles selected by what they DECLARE (the embed capability) rather than named. Keyed in the
    // installed map by the bundle's OWN Bundle-SymbolicName — they were discovered by capability,
    // never named by the caller, so OSGi's native identity is the only honest key.
    for (String ldapFilter : matchFilters) {
      for (BundleLocation location : classpath.matching(ldapFilter)) {
        final Bundle bundle = installAt(location);
        installedBundles.put(bundle.getSymbolicName(), bundle);
        toStart.add(bundle);
      }
    }
    // Start each installed bundle, tolerating one that cannot resolve YET. A package-only bundle
    // whose mandatory imports arrive LATER — scenario-engine imports seed-broker-codec (+
    // optionally
    // jGiven), pulled in the test body by installImportClosureOf of the host — cannot resolve here;
    // it has no Bundle-Activator and nothing needs it ACTIVE (its runner package is class-loaded
    // when
    // the host runs the launcher), so its start() throws and is swallowed, leaving it installed for
    // the test-body resolve to wire. Per-bundle (not a batch resolveBundles): a batch containing
    // the
    // deferred bundle resolves NONE, but starting each independently lets the library + JUnit
    // bundles
    // (which CAN resolve) reach ACTIVE while only the deferred one is skipped.
    final BundleInstaller installer = new BundleInstaller(context());
    for (Bundle bundle : toStart) {
      try {
        installer.startIfNotFragment(bundle);
      } catch (BundleException deferred) {
        LOG.debug(
            "bundle {} not started in beforeAll — deferred to the test-body resolve: {}",
            bundle.getSymbolicName(),
            deferred.getMessage());
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
    return framework().getBundleContext();
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
    final String hostBsn =
        BundleManifest.from(fragmentLocation)
            .flatMap(BundleManifest::fragmentHost)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "fixture matching "
                            + ldapFilter
                            + " declares no Fragment-Host — not a -test fragment"));
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
    return new BundleInstaller(context()).install(location);
  }

  /**
   * Start each bundle that is not a fragment (the shared install/start gesture prod's {@code
   * FrameworkLauncher} runs on each installable): SCR only activates the {@code @Component}s of
   * ACTIVE bundles, so a scenario that resolves a REAL sibling service — not a mock — needs its
   * whole graph started, not merely resolved. Fragments are skipped (they merge into their host).
   * The complement of {@link #installImportClosureOf}: install the closure, then start it.
   */
  public void startAll(List<Bundle> bundles) throws Exception {
    final BundleInstaller installer = new BundleInstaller(context());
    for (Bundle bundle : bundles) {
      installer.startIfNotFragment(bundle);
    }
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
    final boolean resolved = framework().adapt(FrameworkWiring.class).resolveBundles(bundles);
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
    final var wiring = framework().adapt(FrameworkWiring.class);
    final List<String> unsatisfied = new ArrayList<>();
    for (var requirement : bundle.adapt(BundleRevision.class).getDeclaredRequirements(null)) {
      // An OPTIONAL requirement with no provider does NOT block resolution, so it is noise in a
      // resolution-failure post-mortem — listing it points at the wrong package (e.g. a host-side
      // seam import a bundle legitimately lacks in-container). Only mandatory unmet requirements
      // are
      // the actionable cause.
      if ("optional".equals(requirement.getDirectives().get("resolution"))) {
        continue;
      }
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
   * Install+start felix.resolver (the DEFAULT — see {@link Builder#startResolver}), so its
   * Bundle-Activator registers the {@code org.osgi.service.resolver.Resolver} service the manifests
   * + unitrepo synthesis {@code @Component}s reference. The twin of {@link #startScr()}, simpler:
   * it imports only framework packages the system bundle already exports ({@code
   * org.osgi.resource}, {@code org.osgi.service.resolver}, {@code org.osgi.framework}), so no
   * import closure to walk — install and start.
   *
   * <p>TOLERANT of absence, unlike {@link #startScr()}: the Resolver is a NICHE provider (only
   * synthesis worlds consume it), not the norm SCR is, and it is carried by bundle-test-parent — so
   * a world tested off that parent (scenario-engine's own tests, bench) simply does not have the
   * jar on its classpath. Absent → skip (a world that installs no Resolver-consuming bundle needs
   * none); present but stalled → fail loud. A world that HAS the jar but wants it off opts out with
   * {@link Builder#withoutResolver()}.
   */
  private void startResolver() throws Exception {
    if (!classpath.contains("org.apache.felix.resolver")) {
      return;
    }
    final Bundle resolver = installAt(classpath.locateBySymbolicName("org.apache.felix.resolver"));
    resolver.start();
    if (resolver.getState() != Bundle.ACTIVE) {
      throw new IllegalStateException(
          "felix.resolver did not reach ACTIVE — the Resolver service is not registered");
    }
  }

  /**
   * Install — WITHOUT starting — the import closure of {@code hosts}: every classpath bundle the
   * hosts transitively import that is neither already installed, system-exported (host-flat), nor a
   * seam. Returns the pulled bundles in discovery order so the caller folds them into the set it
   * resolves with the hosts.
   *
   * <p>This is the host-seeded counterpart of {@link #startScr()} (which seeds the SAME shared
   * {@link BundleIndex#closeOverImports} walk with felix.scr): a test installs its host via {@link
   * #installFixtureWithHost} and lets the framework's own dependency graph pull in what the host
   * needs (its sibling domain bundles, the third-party libraries it imports — jackson, ipaddress),
   * instead of hand-listing them. The walk's already-provided set is what the running SYSTEM BUNDLE
   * exports — the framework's intrinsic packages ({@code org.osgi.framework}, {@code
   * org.osgi.resource}, …) AND the seam packages declared via {@link Builder#systemPackages}, all
   * host-flat — so it never pulls a bundle (e.g. the {@code osgi.core} API jar) for a package the
   * system bundle already serves. {@code exporterOf} also skips seam-typed bundles, so a seam is
   * never pulled. The closure is always in sync with what the host's manifest declares — no list to
   * resynchronise.
   */
  public List<Bundle> installImportClosureOf(Bundle... hosts) throws Exception {
    final List<BundleLocation> seeds = new ArrayList<>();
    for (Bundle host : hosts) {
      seeds.add(classpath.locateBySymbolicName(host.getSymbolicName()));
    }
    final List<Bundle> pulled = new ArrayList<>();
    final List<BundleLocation> pulledLocations = new ArrayList<>();
    final Consumer<BundleLocation> install =
        location -> {
          try {
            final Bundle bundle = installAt(location);
            installedBundles.put(bundle.getSymbolicName(), bundle);
            pulled.add(bundle);
            pulledLocations.add(location);
          } catch (Exception ex) {
            sneaky(ex);
          }
        };
    // Chase two closures to a fixpoint: the package closure (who exports what a bundle imports) and
    // the SERVICE closure (who publishes the osgi.service a bundle @References — the runtime
    // dependency the resolver ignores, marked effective:=active). A service provider may itself
    // import packages, and a package-pulled bundle may @Reference more services, so alternate until
    // neither pulls anything new. The seeds' own services seed the service-provided set so a host
    // that self-publishes a service is not re-pulled.
    final Set<String> providedServices = new LinkedHashSet<>();
    for (BundleLocation seed : seeds) {
      providedServices.addAll(classpath.manifestOf(seed).providedServices());
    }
    int lastSize = -1;
    while (pulled.size() != lastSize) {
      lastSize = pulled.size();
      final List<BundleLocation> frontier = new ArrayList<>(seeds);
      frontier.addAll(pulledLocations);
      classpath.closeOverImports(frontier, systemBundleExports(), install);
      classpath.closeOverServices(frontier, providedServices, install);
    }
    return pulled;
  }

  /**
   * The package names the running system bundle (id 0) exports — its intrinsic framework packages
   * plus {@code system.packages.extra} (the seams). Read from the live framework's own wiring, so
   * the closure walk's already-provided set is exactly what loads host-flat, never a hand-kept
   * mirror.
   */
  private Set<String> systemBundleExports() {
    final Set<String> names = new LinkedHashSet<>();
    for (var capability :
        framework().adapt(BundleWiring.class).getCapabilities(PackageNamespace.PACKAGE_NAMESPACE)) {
      names.add((String) capability.getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE));
    }
    return names;
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
