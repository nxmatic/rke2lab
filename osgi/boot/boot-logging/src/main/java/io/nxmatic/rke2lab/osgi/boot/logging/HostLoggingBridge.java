package io.nxmatic.rke2lab.osgi.boot.logging;

import org.slf4j.bridge.SLF4JBridgeHandler;

/**
 * Routes host {@code java.util.logging} onto slf4j/logback — the HOST-side (JCL) half of the boot's
 * logging convergence, shared by both boot executors (the prod {@code FrameworkLauncher} and the
 * test {@code FelixFrameworkExtension}) so they wire logs identically.
 *
 * <pre>
 *   Felix internal Logger ─► (FelixJulLogger) JUL ─┐
 *   io.grpc / the JDK / SeedLog ──────────────► JUL ─┼─► SLF4JBridgeHandler ─► slf4j ─► logback
 * </pre>
 *
 * <p>Pax-free by design: this carries NO second slf4j binding — it merely reroutes the JVM-global
 * {@code LogManager} onto whatever slf4j binding the consumer already has (logback). The OSGi-side
 * (BCL) {@code LogService} half is pax-logging, which lives in {@code osgi/runtime} only and is
 * never pulled in here, so depending on {@code boot-logging} never puts a second {@code org.slf4j}
 * provider on a test classpath (the scar that broke jGiven in-container resolution).
 */
public final class HostLoggingBridge {

  private HostLoggingBridge() {}

  /**
   * Drop JUL's default console handlers (so a record is not printed twice) and install the slf4j
   * bridge. Idempotent — re-installing is a no-op once present, so every boot can call it without
   * coordinating. Pair with {@code LevelChangePropagator} in the host logback config so logback
   * levels filter JUL at the source rather than after the bridge ships every record.
   */
  public static void install() {
    if (!SLF4JBridgeHandler.isInstalled()) {
      SLF4JBridgeHandler.removeHandlersForRootLogger();
      SLF4JBridgeHandler.install();
    }
  }
}
