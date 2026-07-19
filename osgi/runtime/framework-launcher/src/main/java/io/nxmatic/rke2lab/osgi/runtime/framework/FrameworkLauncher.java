package io.nxmatic.rke2lab.osgi.runtime.framework;

import io.nxmatic.rke2lab.osgi.bnd.BootStackJar;
import io.nxmatic.rke2lab.osgi.boot.discovery.BootPlan;
import io.nxmatic.rke2lab.osgi.boot.discovery.BundleInstaller;
import io.nxmatic.rke2lab.osgi.boot.discovery.BundleLocation;
import io.nxmatic.rke2lab.osgi.boot.logging.FelixJulLogger;
import io.nxmatic.rke2lab.osgi.boot.logging.HostLoggingBridge;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;
import org.osgi.framework.startlevel.BundleStartLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The boot ACT: launches an embedded Felix from a {@link BootPlan} (the boot DECISION, computed
 * framework-free by {@code BootPlanner}) and a {@link LaunchConfig} (the few honest knobs that
 * differ between executors), and returns a {@link BootedFramework} — the host seam. The only code
 * in the system that touches {@code org.osgi.framework.launch}. Shared by the prod executor and the
 * test harness so both provision the framework identically (same start-level activation, same
 * logging setup) — a test cannot pass for a boot prod would fail.
 *
 * <p>This is where the host LOGGING substrate is set up, because the launch is what owns it:
 *
 * <ul>
 *   <li>the {@link SLF4JBridgeHandler jul→slf4j bridge} routes host {@code java.util.logging}
 *       emitters (io.grpc, the JDK, SeedLog) onto slf4j/logback;
 *   <li>a {@link FelixJulLogger} hands Felix's OWN internal output into JUL too (via {@code
 *       felix.log.logger}), so even the framework trace joins that one path — no {@code System.out}
 *       remainder;
 *   <li>{@code pax-logging-logback} (when the plan carries pax) reuses the host's logback context
 *       ({@code StaticLogbackContext=true}) for the OSGi LogService side.
 * </ul>
 *
 * Every application, framework-event and bundle log thus converges on the one logback context.
 */
public final class FrameworkLauncher {

  private static final Logger LOG = LoggerFactory.getLogger(FrameworkLauncher.class);

  private final LaunchConfig config;

  public FrameworkLauncher(LaunchConfig config) {
    this.config = config;
  }

  /** Launch the framework, install+start what the plan decided, and assert SCR if it carries it. */
  public BootedFramework launch(BootPlan plan, boolean assertScr) throws IOException {
    HostLoggingBridge.install();

    final FrameworkFactory factory =
        ServiceLoader.load(FrameworkFactory.class)
            .findFirst()
            .orElseThrow(
                () -> new IllegalStateException("no OSGi FrameworkFactory on the classpath"));

    final Framework framework = factory.newFramework(frameworkConfig(plan));
    try {
      framework.init();

      // Install everything pinned to its layer and marked persistently-started; the framework's
      // native start-level machinery — not a hand-ordered loop — drives activation in level order
      // when we raise its level. Each bundle installs from its BundleLocation: a packaged jar by
      // URL, an exploded dir by reference:, a staged jar by streaming its bytes into Felix's cache.
      for (BootPlan.Installable installable : plan.installables()) {
        installAtLevel(framework, installable.location(), installable.startLevel());
      }

      final CountDownLatch started = new CountDownLatch(1);
      framework
          .getBundleContext()
          .addFrameworkListener(
              event -> {
                if (event.getType() == FrameworkEvent.STARTED) {
                  started.countDown();
                }
              });
      framework.start();
      if (!started.await(30, TimeUnit.SECONDS)) {
        throw new IOException(
            "OSGi framework did not reach start level " + plan.beginningStartLevel());
      }

      if (assertScr) {
        assertScrActive(framework);
      }
      LOG.info(
          "OSGi runtime booted: {} bundle(s) installed and started", plan.installables().size());
      return new BootedFramework(framework);
    } catch (BundleException ex) {
      throw new IOException("failed to boot OSGi runtime", ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted booting OSGi runtime", ex);
    }
  }

  private Map<String, String> frameworkConfig(BootPlan plan) throws IOException {
    final Path storage;
    try {
      storage = Files.createTempDirectory("osgi-runtime-felix");
    } catch (IOException ex) {
      throw new IOException("failed to create Felix storage dir", ex);
    }
    final Map<String, Object> config = new HashMap<>();
    config.put(Constants.FRAMEWORK_STORAGE, storage.toString());
    config.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
    // The Felix invariants every boot shares (felix.bootdelegation.implicit=false) — one source,
    // so the live and test boots can never diverge on them (§
    // LaunchConfig.applyFrameworkInvariants).
    LaunchConfig.applyFrameworkInvariants(config);
    // Felix's own internal logger routes into JUL (→ the bridge → logback) instead of System.out.
    config.put("felix.log.logger", new FelixJulLogger());
    if (config.get("felix.log.level") == null) {
      this.config
          .frameworkLogLevel()
          .ifPresent(
              level ->
                  config.put(
                      "felix.log.level", Integer.toString(LaunchConfig.felixLevelOf(level))));
    }
    config.put(
        Constants.FRAMEWORK_BEGINNING_STARTLEVEL, Integer.toString(plan.beginningStartLevel()));
    if (!plan.systemPackagesExtra().isEmpty()) {
      config.put(
          Constants.FRAMEWORK_SYSTEMPACKAGES_EXTRA, String.join(",", plan.systemPackagesExtra()));
    }
    if (!config().bootDelegation().isEmpty()) {
      config.put(Constants.FRAMEWORK_BOOTDELEGATION, String.join(",", config().bootDelegation()));
    }
    if (plan.paxPresent()) {
      // pax-logging-logback reuses the host's logback LoggerContext (one context, host-owned)
      // rather
      // than a private one; and drains framework/bundle/service events at WARN into the LogService.
      config.put("org.ops4j.pax.logging.StaticLogbackContext", "true");
      config.put("org.ops4j.pax.logging.service.frameworkEventsLogLevel", "WARN");
    }
    // Felix accepts a Map<String,Object> (the logger is an instance); the launch API is
    // String-keyed
    // but values may be objects, so cast at the boundary the framework factory expects.
    @SuppressWarnings("unchecked")
    final Map<String, String> typed = (Map<String, String>) (Map<String, ?>) config;
    return typed;
  }

  private LaunchConfig config() {
    return config;
  }

  /**
   * Install a bundle from its {@link BundleLocation}, pin it to {@code startLevel}, mark it
   * persistently-started. A staged bundle streams its bytes into Felix's cache; a classpath jar/dir
   * installs by its location URL — the one place the boot's runtime NATURE shows (a sealed switch),
   * everything upstream being source-agnostic.
   */
  private static void installAtLevel(Framework framework, BundleLocation location, int startLevel)
      throws BundleException, IOException {
    final BundleInstaller installer = new BundleInstaller(framework.getBundleContext());
    final Bundle bundle = installer.install(location);
    bundle.adapt(BundleStartLevel.class).setStartLevel(startLevel);
    // A fragment has no lifecycle of its own — calling start() on it throws. It is installed and
    // left to be merged into its host when the host resolves (OSGi Core §3.14), exactly as the test
    // harness installs a -test fragment. The shared installer skips fragments by their header.
    installer.startIfNotFragment(bundle);
  }

  /**
   * Assert felix.scr actually activated — the proof DS is running. We check the felix.scr BUNDLE
   * reached {@code ACTIVE}, NOT that the host can see {@code ServiceComponentRuntime}: that
   * service's package is felix.scr-internal, wired bundle-to-bundle and deliberately NOT
   * system-exported. A stalled felix.scr (an unsatisfied mandatory import) stays INSTALLED; the
   * dump names every bundle's state so the unresolved one is obvious.
   */
  private static void assertScrActive(Framework framework) {
    for (Bundle b : framework.getBundleContext().getBundles()) {
      if (BootStackJar.FELIX_SCR.symbolicName().equals(b.getSymbolicName())
          && b.getState() == Bundle.ACTIVE) {
        return;
      }
    }
    final StringBuilder dump = new StringBuilder();
    for (Bundle b : framework.getBundleContext().getBundles()) {
      dump.append("\n  ").append(stateName(b.getState())).append("  ").append(b.getSymbolicName());
    }
    throw new IllegalStateException(
        "felix.scr did not reach ACTIVE — DS is not running; bundle states:" + dump);
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
}
