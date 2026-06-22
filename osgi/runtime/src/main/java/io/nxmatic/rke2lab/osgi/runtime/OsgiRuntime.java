package io.nxmatic.rke2lab.osgi.runtime;

import io.nxmatic.rke2lab.osgi.bnd.BootStackJar;
import io.nxmatic.rke2lab.osgi.bnd.Clause;
import io.nxmatic.rke2lab.osgi.boot.discovery.BundleIndex;
import io.nxmatic.rke2lab.osgi.boot.discovery.BundleLocation;
import io.nxmatic.rke2lab.osgi.boot.discovery.BundleManifest;
import io.nxmatic.rke2lab.osgi.boot.discovery.DiscoveryPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
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
 * host-world EXECUTOR of the boot MODEL declared in {@code osgi/boot/boot-discovery}; the test-only
 * {@code FelixFrameworkExtension} is the other executor of the same model.
 *
 * <p>The {@code system.packages.extra} the bundles need is DERIVED, not hand-listed: the runtime
 * reads each bundle's bnd-computed {@code Import-Package} header (via {@link BundleManifest}) and
 * re-exports those packages from the system bundle, so the bundle resolves against the host's flat
 * classpath (jackson, cdk8s, the {@code -port} contracts) while sharing ONE copy of each class —
 * typed access across the seam, no reflection. This keeps the export set in lock-step with what bnd
 * actually computed.
 *
 * <p>Every bundle — boot stack, model, edge — is a {@link BundleLocation}, found by the identity it
 * DECLARES through a {@link BundleIndex}, never by a file name. Two topologies share this one
 * install path: the classpath topology (reactor tests pass located bundles) and the embedded
 * topology ({@link #embeddedBootStack()} discovers everything from the jars staged under {@code
 * META-INF/bundles/}).
 */
public final class OsgiRuntime implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(OsgiRuntime.class);

  /**
   * OSGi start levels drive activation order natively — install everything, pin each bundle to its
   * layer, then let the framework raise its level and start bundles level-by-level. Logging is
   * lowest so the LogService is live before anything else activates; the felix runtime (scr,
   * resolver) next; the model bundles last. The executor's mapping of the model's {@link
   * BootStackJar.Layer} roles onto concrete levels.
   */
  /**
   * A passive spec/library jar (e.g. the DS-API trio): no activator, it only needs to be resolvable
   * before anything that imports it. Lowest level so it precedes the boot stack that wires to it.
   */
  private static final int START_LEVEL_PASSIVE = 1;

  private static final int START_LEVEL_LOGGING = 2;

  private static final int START_LEVEL_FRAMEWORK_RUNTIME = 3;

  private static final int START_LEVEL_BUNDLES = 4;

  /**
   * The bundles staged under {@code META-INF/bundles/} in this exec-jar (empty off the exec-jar) —
   * the source for {@link #hasEmbeddedBundles()}, the pre-construction "is this an exec-jar" probe
   * a caller reads to choose {@link #embeddedBootStack()} over a classpath builder.
   */
  private static final BundleIndex STAGED =
      BundleIndex.ofStagedBundles(OsgiRuntime.class.getClassLoader());

  /** Pax Logging, at the LogService layer (api then backend), in declaration order. */
  private final List<BundleLocation> paxLoggingBundles;

  private final List<BundleLocation> runtimeBundles;
  private final List<BundleLocation> modelBundles;
  private final boolean startScr;
  private final boolean embedsBootStack;
  private final DiscoveryPolicy discoveryPolicy;
  private final Set<String> explicitSystemPackages;

  /**
   * Where this runtime DISCOVERS bundles — a collaborator, so reading the composition shows the
   * runtime does discovery rather than hiding it in a method body. The embedded boot reads the
   * staged index (boot-stack jars by symbolic name, model/edge bundles by the embed capability); a
   * classpath/test boot reads the classpath index (where the builder's located bundles live, and
   * which the seam guard queries to attribute a package to its owning domain bundle).
   */
  private final BundleIndex discovery;

  private Framework framework;

  private OsgiRuntime(Builder builder) {
    this.paxLoggingBundles = List.copyOf(builder.paxLoggingBundles);
    this.runtimeBundles = List.copyOf(builder.runtimeBundles);
    this.modelBundles = List.copyOf(builder.modelBundles);
    this.startScr = builder.startScr;
    this.embedsBootStack = builder.embedsBootStack;
    this.discoveryPolicy = builder.discoveryPolicy;
    this.explicitSystemPackages = new LinkedHashSet<>(builder.systemPackages);
    this.discovery = builder.embedsBootStack ? STAGED : BundleIndex.ofClasspath();
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * A builder pre-loaded to boot the whole embedded stack from the jars staged under {@code
   * META-INF/bundles/}: {@link #boot()} locates the {@link BootStackJar} entries by their symbolic
   * name and discovers the model + edge bundles by the embed capability — no per-entrypoint
   * variation to add. Every exec entrypoint (seed-master + the two CLIs) just calls {@code
   * embeddedBootStack().build()}, so a boot change is made once here.
   */
  public static Builder embeddedBootStack() {
    return builder().withScr().embedBootStack();
  }

  /**
   * Whether the running process carries the embedded boot stack — true in a deployed exec-jar,
   * false on a reactor/test classpath. Probes felix.scr (by its symbolic name), the boot-stack
   * bundle common to every entrypoint, so the check is uniform across seed-master and the CLIs.
   */
  public static boolean hasEmbeddedBundles() {
    return STAGED.contains(BootStackJar.FELIX_SCR.symbolicName());
  }

  /** Declares the framework topology booted by {@link #boot()}. */
  public static final class Builder {
    private final List<BundleLocation> paxLoggingBundles = new ArrayList<>();
    private final List<BundleLocation> runtimeBundles = new ArrayList<>();
    private final List<BundleLocation> modelBundles = new ArrayList<>();
    private final List<String> systemPackages = new ArrayList<>();
    private boolean embedsBootStack;
    private boolean startScr;
    private DiscoveryPolicy discoveryPolicy = DiscoveryPolicy.all();

    /**
     * Install + start felix.scr before the bundles. The DS-runtime API it imports
     * (org.osgi.service.component / util.promise / util.function) is no longer system-exported: the
     * staging extension stages those spec jars as bundles, so felix.scr wires to them bundle-to-
     * bundle — system-exporting them would re-export off the flat classpath and split the class.
     */
    public Builder withScr() {
      this.startScr = true;
      return this;
    }

    /**
     * Install + start Pax Logging FIRST (before felix.scr and the bundles) so the OSGi LogService
     * is live before anything logs. {@code paxLoggingApi} provides {@code org.slf4j} to bundles (so
     * the runtime stops system-exporting it); {@code paxLoggingLogback} is the LogService backend
     * that — with {@code StaticLogbackContext=true} — reuses the HOST's logback context. Order
     * matters: api first, then the backend.
     */
    public Builder withPaxLogging(BundleLocation paxLoggingApi, BundleLocation paxLoggingLogback) {
      this.paxLoggingBundles.add(paxLoggingApi);
      this.paxLoggingBundles.add(paxLoggingLogback);
      return this;
    }

    /** A felix runtime bundle (e.g. {@code org.apache.felix.scr}) to install + start. */
    public Builder runtimeBundle(BundleLocation bundle) {
      this.runtimeBundles.add(bundle);
      return this;
    }

    /**
     * A model/edge bundle to install + start; its {@code Import-Package} is mirrored as a system
     * export.
     */
    public Builder bundle(BundleLocation bundle) {
      this.modelBundles.add(bundle);
      return this;
    }

    /**
     * Boot the whole stack from the staged bundles: {@link #boot()} locates the {@link
     * BootStackJar} entries by symbolic name and discovers the model + edge bundles by the embed
     * capability. The embedded-deployment counterpart of {@link #withPaxLogging}/{@link
     * #runtimeBundle}/{@link #bundle}; the boot stack is fixed and identical across entrypoints, so
     * nothing per-jar to pass.
     */
    public Builder embedBootStack() {
      this.embedsBootStack = true;
      return this;
    }

    /**
     * How the embedded boot selects which discovered bundles to install — defaults to {@link
     * DiscoveryPolicy#all()} (feed Felix every bundle the index carries, let its resolver wire
     * them). Override to pin a deterministic subset: {@code discover(DiscoveryPolicy.allExcept(…))}
     * or {@code discover(DiscoveryPolicy.only(…))}. The SAME knob the test harness exposes, so prod
     * and test choose their topology through one API. Only meaningful with {@link #embedBootStack}.
     */
    public Builder discover(DiscoveryPolicy policy) {
      this.discoveryPolicy = policy;
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

  private static String stateName(int state) {
    return switch (state) {
      case Bundle.UNINSTALLED -> "UNINSTALLED";
      case Bundle.INSTALLED -> "INSTALLED ";
      case Bundle.RESOLVED -> "RESOLVED  ";
      case Bundle.STARTING -> "STARTING  ";
      case Bundle.STOPPING -> "STOPPING  ";
      case Bundle.ACTIVE -> "ACTIVE    ";
      default -> "?(" + state + ")";
    };
  }

  private static int startLevelFor(BootStackJar.Layer layer) {
    return switch (layer) {
      case LOGGING -> START_LEVEL_LOGGING;
      case FRAMEWORK_RUNTIME -> START_LEVEL_FRAMEWORK_RUNTIME;
    };
  }

  /**
   * The start level a discovered bundle installs at, by its ROLE:
   *
   * <ul>
   *   <li>a boot-stack jar (felix.scr / resolver / pax, matched by symbolic name) takes its {@link
   *       BootStackJar.Layer};
   *   <li>a model/edge bundle (embed capability) takes the bundle level — it activates last;
   *   <li>anything else is a PASSIVE spec/library jar (e.g. the DS-API trio, which felix.scr
   *       imports): it has no activator, only needs to RESOLVE, and must do so BEFORE the bundle
   *       that imports it activates. felix.scr sits at FRAMEWORK_RUNTIME, so the trio cannot wait
   *       at the bundle level (above scr) or scr activates first and never sees
   *       ServiceComponentRuntime. Pin it at the lowest level so it is resolvable before anything
   *       that wires to it.
   * </ul>
   */
  private static int startLevelOf(BundleManifest manifest) {
    final String bsn = manifest.symbolicName();
    for (BootStackJar jar : BootStackJar.values()) {
      if (jar.symbolicName().equals(bsn)) {
        return startLevelFor(jar.layer());
      }
    }
    if (manifest.embed() != null && manifest.embed().isDomain()) {
      return START_LEVEL_BUNDLES;
    }
    return START_LEVEL_PASSIVE; // a passive spec/library jar — resolvable before all importers.
  }

  /** An installable bundle: where its bytes live, and the start level its layer maps to. */
  private record Installable(BundleLocation location, int startLevel) {}

  /**
   * Pull every passive jar the stack's MANDATORY imports need into the stack — the runtime mirror
   * of the build-time {@code StagingClosure}. For each stacked bundle's import that (a) is
   * mandatory, (b) no stacked bundle already exports, and (c) the host classpath does NOT carry (so
   * it cannot resolve against the system bundle — the DS-API trio, removed from
   * system.packages.extra; jackson stays out, it IS host-flat), find the index jar that exports it
   * and add it passively. A fixpoint: a pulled-in jar's own imports are closed over in turn.
   * Idempotent on the embedded topology (the policy already staged the trio); it is the classpath
   * topology this makes resolve the same set.
   */
  private void closeOverImports(List<Installable> stack, Set<String> systemExported)
      throws IOException {
    final Set<String> stackGas = new LinkedHashSet<>();
    final Set<String> exportedByStack = new LinkedHashSet<>();
    for (Installable i : stack) {
      final BundleManifest m = BundleManifest.from(i.location());
      stackGas.add(i.location().locationId());
      exportedByStack.addAll(m.exports().names());
    }
    final Deque<BundleLocation> frontier = new ArrayDeque<>();
    stack.forEach(i -> frontier.add(i.location()));
    while (!frontier.isEmpty()) {
      final BundleManifest manifest = BundleManifest.from(frontier.removeFirst());
      for (Clause imported : manifest.imports().clauses()) {
        final String pkg = imported.name();
        if ("optional".equals(imported.attributes().get("resolution"))
            || exportedByStack.contains(pkg)
            || systemExported.contains(pkg)) {
          // optional, already provided by a stacked bundle, or served by the system bundle
          // (mirrored from a model/edge import — host-flat, e.g. jackson). NOT host-flat-but-
          // unmirrored: the DS-API trio is host-flat in a reactor test yet only felix.scr imports
          // it, so it is NOT system-exported and MUST be wired bundle-to-bundle — pull it in.
          continue;
        }
        final BundleLocation exporter = discovery.exporterOf(pkg);
        if (exporter == null || stackGas.contains(exporter.locationId())) {
          continue;
        }
        final BundleManifest exporterManifest = BundleManifest.from(exporter);
        stack.add(new Installable(exporter, startLevelOf(exporterManifest)));
        stackGas.add(exporter.locationId());
        exportedByStack.addAll(exporterManifest.exports().names());
        frontier.add(exporter);
      }
    }
  }

  /** Boot the framework, install+start the boot stack (if requested) and the model bundles. */
  public OsgiRuntime boot() throws IOException {
    // Resolve the topology into one ordered list of (location, start level). The classpath topology
    // took its bundles explicitly through the builder; the embedded topology DISCOVERS them from
    // the
    // index via the DiscoveryPolicy (default: install everything the index carries, the launcher
    // already excluded). Either way every bundle is a BundleLocation, so the install loop below is
    // source-agnostic. The start level is the bundle's ROLE: pax at logging, felix.scr/resolver at
    // framework-runtime (by their BootStackJar symbolic name), everything else at the bundle level
    // —
    // a spec jar like the DS-API trio installs there too, passively (no activator, it only resolves
    // so felix.scr wires to it).
    final List<Installable> stack = new ArrayList<>();
    // The domain (model/edge) bundles whose Import-Package feeds deriveSystemExports —
    // builder-given
    // ones plus those discovered in the embedded topology.
    final List<BundleLocation> models = new ArrayList<>(modelBundles);
    for (BundleLocation pax : paxLoggingBundles) {
      stack.add(new Installable(pax, START_LEVEL_LOGGING));
    }
    for (BundleLocation runtime : runtimeBundles) {
      stack.add(new Installable(runtime, START_LEVEL_FRAMEWORK_RUNTIME));
    }
    // Classpath topology: the builder named its model/edge bundles explicitly.
    for (BundleLocation model : modelBundles) {
      stack.add(new Installable(model, START_LEVEL_BUNDLES));
    }
    // Embedded topology: discover from the index via the policy. Each bundle installs at the start
    // level its role maps to; domain bundles also feed the system-export derivation.
    if (embedsBootStack) {
      for (BundleLocation location : discoveryPolicy.select(discovery)) {
        final BundleManifest manifest = discovery.manifestOf(location);
        stack.add(new Installable(location, startLevelOf(manifest)));
        if (manifest.embed() != null && manifest.embed().isDomain()) {
          models.add(location);
        }
      }
    }

    final Set<String> exports = deriveSystemExports(models, discovery);

    // Close over the stack's MANDATORY imports: a package a stacked bundle imports that no stacked
    // bundle exports and the system bundle does not export either (so it cannot resolve host-flat),
    // but a jar in the index does, pulls that jar in as a passive bundle — the DS-API trio
    // felix.scr
    // imports. The runtime mirror of the build-time StagingClosure, so a classpath boot resolves
    // the
    // same set the embedded boot stages, without naming the trio. Passing the system-export set
    // keeps a host-flat package (jackson, mirrored from a model import) from being pulled as
    // bundle.
    closeOverImports(
        stack,
        exports.stream()
            .map(e -> Clause.parse(e).name())
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));

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
    // Felix defaults felix.bootdelegation.implicit=true: it GUESSES, by stack inspection, when a
    // class-load instigated from OUTSIDE a bundle should fall through to the parent (app)
    // classloader.
    // That is exactly the silent escape hatch that lets a non-wired package resolve by accident —
    // and
    // would let a seam package be served by the flat parent instead of the single declared
    // exporter,
    // making a typed-seam proof pass for the wrong reason. Off: every load not satisfied by a
    // bundle's
    // imports / Bundle-ClassPath / the system bundle fails loudly. Deterministic by construction.
    config.put("felix.bootdelegation.implicit", "false");
    // The framework climbs to this level when started, activating each layer in turn (§4.3).
    config.put(Constants.FRAMEWORK_BEGINNING_STARTLEVEL, Integer.toString(START_LEVEL_BUNDLES));
    if (!exports.isEmpty()) {
      config.put(Constants.FRAMEWORK_SYSTEMPACKAGES_EXTRA, String.join(",", exports));
    }
    // Pax Logging present either explicitly (withPaxLogging) or via the embedded boot stack — both
    // need the same two pax effects below; keying only on the explicit list silently skipped them
    // for the embedded boot.
    final boolean paxLoggingPresent = !paxLoggingBundles.isEmpty() || embedsBootStack;
    if (paxLoggingPresent) {
      // pax-logging-logback reuses the host's logback LoggerContext (one context, host-owned)
      // rather
      // than a private one; and drains framework/bundle/service events at WARN into the LogService.
      config.put("org.ops4j.pax.logging.StaticLogbackContext", "true");
      config.put("org.ops4j.pax.logging.service.frameworkEventsLogLevel", "WARN");
    }

    framework = factory.newFramework(config);
    try {
      framework.init();

      // Install everything pinned to its layer and marked persistently-started; the framework's
      // native start-level machinery — not a hand-ordered loop — drives activation in level order
      // when we raise its level. Declaration order within a level is preserved (pax-api before its
      // backend). Each bundle installs from its BundleLocation: a packaged jar by URL, an exploded
      // dir by reference:, a staged jar by streaming its bytes into Felix's cache — one path.
      for (Installable installable : stack) {
        installAtLevel(installable.location(), installable.startLevel());
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

      if (startScr) {
        assertScrActive();
      }
      LOG.info("OSGi runtime booted: {} bundle(s) installed and started", stack.size());
    } catch (BundleException ex) {
      throw new IOException("failed to boot OSGi runtime", ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted booting OSGi runtime", ex);
    }
    return this;
  }

  /**
   * The {@code system.packages.extra} set: each model bundle's {@code Import-Package} mirrored as a
   * system export, minus packages an installed bundle exports itself (it is the sole provider
   * inside the framework — re-exporting from the system bundle would split the class). Fails fast
   * on a remaining package the host classloader cannot resolve — a real missing dependency,
   * surfaced here rather than as an opaque NoClassDefFoundError once SCR injects.
   */
  private Set<String> deriveSystemExports(List<BundleLocation> models, BundleIndex discovery)
      throws IOException {
    final Set<String> exports = new LinkedHashSet<>(explicitSystemPackages);
    final List<BundleManifest> manifests = new ArrayList<>();
    for (BundleLocation model : models) {
      manifests.add(BundleManifest.from(model));
    }
    final Set<String> bundleExportedPackages = new LinkedHashSet<>();
    for (BundleManifest manifest : manifests) {
      bundleExportedPackages.addAll(manifest.exports().names());
    }
    for (BundleManifest manifest : manifests) {
      exports.addAll(manifest.imports().asSystemExports());
    }
    exports.removeIf(e -> bundleExportedPackages.contains(Clause.parse(e).name()));
    // The seam guard. A package owned by a domain bundle (type=model/edge) loads on the BUNDLE side
    // of the seam: its bundle is the sole exporter, never the system bundle. If such a package
    // still
    // sits in `exports` here, its exporter is NOT in the install set, so it would leak into
    // system.packages.extra and split the class against the bundle's own copy. The packages removed
    // just above (exported by an installed bundle) are the wired-bundle-to-bundle case and are
    // gone;
    // what remains and resolves to a domain exporter is the leak. Seam packages (type=seam, the
    // -port membrane) resolve to null here and stay — that is exactly what belongs in the seam.
    final List<String> leaked =
        exports.stream()
            .map(e -> Clause.parse(e).name())
            .flatMap(
                p -> {
                  final String exporter = discovery.domainExporterOf(p);
                  return exporter == null
                      ? Stream.empty()
                      : Stream.of(p + " (owned by domain bundle " + exporter + ")");
                })
            .toList();
    if (!leaked.isEmpty()) {
      throw new IllegalStateException(
          "system.packages.extra would leak domain packages whose owning bundle is not installed — "
              + "install the owning bundle (wire it bundle-to-bundle) instead of system-exporting it: "
              + leaked);
    }
    final List<String> unresolved =
        exports.stream().map(e -> Clause.parse(e).name()).filter(p -> !hostResolves(p)).toList();
    if (!unresolved.isEmpty()) {
      throw new IllegalStateException(
          "system.packages.extra would export packages absent from the host classpath: "
              + unresolved);
    }
    // pax-logging-api provides org.slf4j to bundles; a second provider (the system bundle
    // re-exporting it off the flat classpath) would split the slf4j binder — the R1 scar. Drop
    // org.slf4j so pax is the sole provider inside the framework.
    if (!paxLoggingBundles.isEmpty() || embedsBootStack) {
      exports.removeIf(e -> e.equals("org.slf4j") || e.startsWith("org.slf4j;"));
    }
    return exports;
  }

  /**
   * Install a bundle from its {@link BundleLocation}, pin it to {@code startLevel}, and mark it
   * persistently-started. While the framework's current level is below {@code startLevel} the
   * bundle stays {@code INSTALLED}; the framework activates it when it climbs to that level — no
   * manual {@code .start()} ordering. A staged bundle streams its bytes into Felix's cache
   * (installBundle copies them); a classpath jar/dir installs by its location URL — no temp file
   * either way.
   */
  private void installAtLevel(BundleLocation location, int startLevel)
      throws BundleException, IOException {
    final Bundle bundle =
        switch (location) {
          case BundleLocation.Staged staged -> {
            try (var in = staged.open()) {
              yield context().installBundle(staged.locationId(), in);
            }
          }
          case BundleLocation.OnClasspath onClasspath ->
              context().installBundle(onClasspath.locationId());
        };
    bundle.adapt(BundleStartLevel.class).setStartLevel(startLevel);
    bundle.start();
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

  /**
   * Assert felix.scr actually activated — the proof that DS is running. We check the felix.scr
   * BUNDLE reached {@code ACTIVE}, NOT that the host can see {@code ServiceComponentRuntime}: that
   * service's package ({@code org.osgi.service.component.runtime}) is felix.scr-internal, wired
   * bundle-to-bundle and deliberately NOT system-exported to the flat host, so the host cannot (and
   * must not) resolve it. A stalled felix.scr (an unsatisfied mandatory import) stays INSTALLED;
   * the dump names every bundle's state so the unresolved one is obvious.
   */
  private void assertScrActive() {
    final Bundle scr = bundleBySymbolicName(BootStackJar.FELIX_SCR.symbolicName());
    if (scr != null && scr.getState() == Bundle.ACTIVE) {
      return;
    }
    final StringBuilder dump = new StringBuilder();
    for (Bundle b : framework.getBundleContext().getBundles()) {
      dump.append("\n  ").append(stateName(b.getState())).append("  ").append(b.getSymbolicName());
    }
    throw new IllegalStateException(
        "felix.scr did not reach ACTIVE — DS is not running; bundle states:" + dump);
  }

  private Bundle bundleBySymbolicName(String symbolicName) {
    for (Bundle b : framework.getBundleContext().getBundles()) {
      if (symbolicName.equals(b.getSymbolicName())) {
        return b;
      }
    }
    return null;
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
}
