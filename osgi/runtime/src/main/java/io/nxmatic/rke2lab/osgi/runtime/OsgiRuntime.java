package io.nxmatic.rke2lab.osgi.runtime;

import io.nxmatic.rke2lab.osgi.boot.discovery.BootStackJar;
import io.nxmatic.rke2lab.osgi.boot.discovery.BundleIndex;
import io.nxmatic.rke2lab.osgi.boot.discovery.BundleLocation;
import io.nxmatic.rke2lab.osgi.boot.discovery.BundleManifest;
import io.nxmatic.rke2lab.osgi.boot.discovery.EmbedCapability;
import java.io.IOException;
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

  private static int startLevelFor(BootStackJar.Layer layer) {
    return switch (layer) {
      case LOGGING -> START_LEVEL_LOGGING;
      case FRAMEWORK_RUNTIME -> START_LEVEL_FRAMEWORK_RUNTIME;
    };
  }

  /** An installable bundle: where its bytes live, and the start level its layer maps to. */
  private record Installable(BundleLocation location, int startLevel) {}

  /** Boot the framework, install+start the boot stack (if requested) and the model bundles. */
  public OsgiRuntime boot() throws IOException {
    // Resolve the topology into one ordered list of (location, start level). The embedded topology
    // DISCOVERS — boot-stack jars by symbolic name, model/edge bundles by the embed capability —
    // from the staged index; the classpath topology took its bundles explicitly through the
    // builder.
    // Either way every bundle is a BundleLocation, so the install loop below is source-agnostic.
    final List<Installable> stack = new ArrayList<>();
    final List<BundleLocation> models = new ArrayList<>(modelBundles);
    for (BundleLocation pax : paxLoggingBundles) {
      stack.add(new Installable(pax, START_LEVEL_LOGGING));
    }
    for (BundleLocation runtime : runtimeBundles) {
      stack.add(new Installable(runtime, START_LEVEL_FRAMEWORK_RUNTIME));
    }
    if (embedsBootStack) {
      for (BootStackJar jar : BootStackJar.values()) {
        stack.add(
            new Installable(
                discovery.locateBySymbolicName(jar.symbolicName()), startLevelFor(jar.layer())));
      }
      models.addAll(discovery.matching(EmbedCapability.INSTALL_FILTER));
    }
    for (BundleLocation model : models) {
      stack.add(new Installable(model, START_LEVEL_BUNDLES));
    }

    final Set<String> exports = deriveSystemExports(models, discovery);

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

      if (startScr
          && awaitServiceByName("org.osgi.service.component.runtime.ServiceComponentRuntime", 5000)
              == null) {
        throw new IllegalStateException(
            "felix.scr reached its start level but ServiceComponentRuntime never appeared");
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
    final Set<String> bundleExportedPackages = new LinkedHashSet<>();
    for (BundleLocation model : models) {
      bundleExportedPackages.addAll(
          BundleManifest.packageNames(model.readHeader(Constants.EXPORT_PACKAGE)));
    }
    for (BundleLocation model : models) {
      exports.addAll(
          BundleManifest.mirrorImportsAsExports(model.readHeader(Constants.IMPORT_PACKAGE)));
    }
    exports.removeIf(e -> bundleExportedPackages.contains(BundleManifest.packageName(e)));
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
            .map(BundleManifest::packageName)
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
        exports.stream().map(BundleManifest::packageName).filter(p -> !hostResolves(p)).toList();
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
