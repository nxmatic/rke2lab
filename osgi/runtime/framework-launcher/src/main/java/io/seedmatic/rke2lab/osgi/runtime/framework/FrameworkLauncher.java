package io.seedmatic.rke2lab.osgi.runtime.framework;

import io.seedmatic.rke2lab.osgi.bnd.BootStackJar;
import io.seedmatic.rke2lab.osgi.boot.discovery.BootPlan;
import io.seedmatic.rke2lab.osgi.boot.discovery.BundleInstaller;
import io.seedmatic.rke2lab.osgi.boot.discovery.BundleLocation;
import io.seedmatic.rke2lab.osgi.boot.logging.FelixJulLogger;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
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
 * <p>The logging convergence is INVERTED onto the OSGi side: every host source rides the JDK {@code
 * java.util.logging} bus — host slf4j binds to {@code slf4j-jdk14}, native emitters (io.grpc, the
 * JDK) already speak JUL, and a {@link FelixJulLogger} hands Felix's OWN internal output into JUL
 * (via {@code felix.log.logger}) so even the framework trace joins the bus. pax-logging-api's
 * {@code JdkHandler} then drains that JUL bus into {@code pax-logging-logback}, the single sink
 * (configured console-free). JUL is the only channel that crosses the OSGi classloader boundary —
 * pax embeds its own private logback — so this is how the host and OSGi worlds converge on one
 * logback context.
 */
public final class FrameworkLauncher {

  private static final Logger LOG = LoggerFactory.getLogger(FrameworkLauncher.class);

  private final LaunchConfig config;

  public FrameworkLauncher(LaunchConfig config) {
    this.config = config;
  }

  /** Launch the framework, install+start what the plan decided, and assert SCR if it carries it. */
  public BootedFramework launch(BootPlan plan, boolean assertScr) throws IOException {
    final FrameworkFactory factory =
        ServiceLoader.load(FrameworkFactory.class)
            .findFirst()
            .orElseThrow(
                () -> new IllegalStateException("no OSGi FrameworkFactory on the classpath"));

    final FelixStorage storage = FelixStorage.create("osgi-runtime-felix");
    boolean handedOff = false;
    try {
      final Framework framework = factory.newFramework(frameworkConfig(plan, storage));
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
      if (plan.paxPresent()) {
        applyPaxLogbackPolicy(framework);
      }
      LOG.info(
          "OSGi runtime booted: {} bundle(s) installed and started", plan.installables().size());
      final BootedFramework booted = new BootedFramework(framework, storage);
      handedOff = true;
      return booted;
    } catch (BundleException ex) {
      throw new IOException("failed to boot OSGi runtime", ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted booting OSGi runtime", ex);
    } finally {
      // Any exit before the framework is handed to a BootedFramework — a failed init, an
      // unresolved graph, a start timeout — owns the storage's cleanup; only a successful handoff
      // transfers that duty to BootedFramework.close().
      if (!handedOff) {
        storage.delete();
      }
    }
  }

  private Map<String, String> frameworkConfig(BootPlan plan, FelixStorage storage)
      throws IOException {
    final Map<String, Object> config = new HashMap<>();
    config.put(Constants.FRAMEWORK_STORAGE, storage.path().toString());
    config.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
    // The Felix invariants every boot shares (felix.bootdelegation.implicit=false) — one source,
    // so the live and test boots can never diverge on them (§
    // LaunchConfig.applyFrameworkInvariants).
    LaunchConfig.applyFrameworkInvariants(config);
    // Felix's own internal logger routes into JUL (→ pax's JdkHandler → pax logback) not
    // System.out.
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
      // pax-logging-logback is the single sink (it drains the JDK JUL bus via its JdkHandler).
      // Point
      // it at a MINIMAL, console-free FILE-only bootstrap config via StaticLogbackFile, so pax
      // configures from it — the FILE appender + root only — instead of its BasicConfigurator
      // default (a ConsoleAppender whose native stdout write wedges the boot under a remote
      // debugger). The noise-suppression POLICY (the per-tree levels) is applied AFTER start by the
      // pax-logback-config fragment's PaxLogbackConfigurer, in Java — see applyPaxLogbackPolicy.
      // And
      // drain framework/bundle/service events at WARN into the LogService.
      config.put(
          "org.ops4j.pax.logging.StaticLogbackFile", writePaxLogbackConfig(storage).toString());
      config.put("org.ops4j.pax.logging.service.frameworkEventsLogLevel", "WARN");
      // pax's fallback logger (used before Config Admin — which we do not install — and while it
      // applies StaticLogbackFile) echoes logback's Joran config status to the console at INFO
      // ("Initializing Logback…", "Setting level of logger…"). That noise corrupts a CLI whose
      // stdout IS the product (netplan-cli's blueprint YAML). Raise the fallback threshold to ERROR
      // so only genuine boot failures reach the console; the file appender still carries INFO.
      config.put("org.ops4j.pax.logging.DefaultServiceLog.level", "ERROR");
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
   * Hand pax-logging-logback's live logback context the Java-coded policy carried by the {@code
   * pax-logback-config} FRAGMENT (its {@code PaxLogbackConfigurer}). The flat host cannot reach
   * logback — pax embeds it and exports no package — so we cross the realm seam the only way there
   * is: load the fragment's configurer THROUGH the pax bundle's own classloader (where it resolves
   * against pax's embedded logback) and invoke it reflectively. Runs after {@code
   * framework.start()} returns: pax is ACTIVE and its minimal bootstrap XML already applied, so
   * this policy is the last word on the context. A failure here must not fell the boot — it
   * degrades to a WARN, leaving pax's bootstrap config in force.
   */
  private void applyPaxLogbackPolicy(Framework framework) {
    final String rootLevel =
        config().frameworkLogLevel().map(LaunchConfig::logbackLevelOf).orElse("INFO");
    for (Bundle bundle : framework.getBundleContext().getBundles()) {
      if (!BootStackJar.PAX_LOGGING_LOGBACK.symbolicName().equals(bundle.getSymbolicName())) {
        continue;
      }
      try {
        bundle
            .loadClass("io.seedmatic.rke2lab.osgi.runtime.logback.PaxLogbackConfigurer")
            .getMethod("configure", Bundle.class, String.class)
            .invoke(null, bundle, rootLevel);
      } catch (InvocationTargetException ex) {
        LOG.warn("pax logback policy not applied", ex.getCause());
      } catch (ReflectiveOperationException | LinkageError ex) {
        LOG.warn("pax logback policy not applied", ex);
      }
      return;
    }
    LOG.warn("pax-logging-logback not found among installed bundles; logback policy not applied");
  }

  /**
   * Write the MINIMAL console-free bootstrap logback config pax-logging-logback loads via {@code
   * StaticLogbackFile} (pax's only external-config channel is {@code
   * JoranConfigurator.doConfigure(File)} — it takes a file, not an object). Deliberately just the
   * FILE appender + root: it exists only to keep pax off its ConsoleAppender default during boot
   * (before {@link #applyPaxLogbackPolicy} runs); the per-tree noise-suppression policy lives in
   * Java, in the {@code pax-logback-config} fragment's {@code PaxLogbackConfigurer}, applied right
   * after start. The file and root level come from {@link LaunchConfig} FIELDS ({@link
   * LaunchConfig#logFile} and {@link LaunchConfig#frameworkLogLevel} — the same knob that drives
   * {@code felix.log.level}, Plane A), baked straight in: no system property, and the launcher
   * stays ignorant of the host/Pulumi. This is the ONE logback in the process: it drains the JDK
   * JUL bus (host + Felix) via pax's JdkHandler AND the OSGi LogService.
   */
  private Path writePaxLogbackConfig(FelixStorage storage) throws IOException {
    final Path file = storage.path().resolve("logback-bootstrap.xml");
    final String rootLevel =
        config().frameworkLogLevel().map(LaunchConfig::logbackLevelOf).orElse("INFO");
    Files.writeString(
        file,
        """
        <configuration>
          <appender name="FILE" class="ch.qos.logback.core.FileAppender">
            <file>__LOG_FILE__</file>
            <append>false</append>
            <encoder>
              <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
            </encoder>
          </appender>
          <root level="__ROOT_LEVEL__">
            <appender-ref ref="FILE"/>
          </root>
        </configuration>
        """
            .replace("__LOG_FILE__", config().logFile())
            .replace("__ROOT_LEVEL__", rootLevel));
    return file;
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
