package io.nxmatic.rke2lab.osgi.boot.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

/**
 * Proves the host-side logging convergence actually DELIVERS — every host {@code java.util.logging}
 * source {@link HostLoggingBridge} routes (the JDK/library path, and Felix's own logger via {@link
 * FelixJulLogger}) reaches the LOGBACK backend, not merely that {@code install()} was called.
 * Captures on the logback side (a {@link ListAppender} on the root context), DOWNSTREAM of the
 * bridge, so a record in the appender means it crossed {@code JUL → SLF4JBridgeHandler → slf4j →
 * logback} for real.
 *
 * <p>This is the volet-3 visibility task closed end-to-end: with the bridge installed, a host JUL
 * emitter (io.grpc/JDK/SeedLog in prod) is level-controllable and visible like any logback logger.
 * Both boot executors call {@link HostLoggingBridge#install()}, so this holds in prod and test.
 */
final class HostLoggingBridgeTest {

  private LoggerContext context;
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void attachAppender() {
    context = (LoggerContext) LoggerFactory.getILoggerFactory();
    final ch.qos.logback.classic.Logger root =
        context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
    // Capture every level so the test asserts DELIVERY, not a level policy.
    root.setLevel(Level.TRACE);
    appender = new ListAppender<>();
    appender.setContext(context);
    appender.start();
    root.addAppender(appender);
    HostLoggingBridge.install();
  }

  @AfterEach
  void detach() {
    context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME).detachAppender(appender);
  }

  @Test
  void jdkJulRecordReachesLogback() {
    final String message = "jdk-jul-record-" + System.nanoTime();
    java.util.logging.Logger.getLogger("com.example.host").info(message);

    assertTrue(
        appender.list.stream().anyMatch(e -> e.getFormattedMessage().equals(message)),
        "a java.util.logging record must cross the bridge into logback");
  }

  @Test
  void felixOwnLoggerReachesLogback() {
    // FelixJulLogger routes Felix's internal output into JUL → the bridge → logback, so Felix's own
    // trace converges on the same backend instead of writing to System.out.
    final String message = "felix-internal-" + System.nanoTime();
    new FelixJulLogger().doLogOut(org.apache.felix.framework.Logger.LOG_INFO, message, null);

    assertTrue(
        appender.list.stream().anyMatch(e -> e.getFormattedMessage().equals(message)),
        "Felix's own logger must reach logback (no System.out remainder)");
  }

  @Test
  void felixErrorMapsToLogbackError() {
    final String message = "felix-error-" + System.nanoTime();
    new FelixJulLogger().doLogOut(org.apache.felix.framework.Logger.LOG_ERROR, message, null);

    final ILoggingEvent event =
        appender.list.stream()
            .filter(e -> e.getFormattedMessage().equals(message))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Felix ERROR did not reach logback"));
    assertEquals(Level.ERROR, event.getLevel(), "Felix LOG_ERROR maps to logback ERROR");
  }

  @Test
  void bridgeInstallIsIdempotent() {
    HostLoggingBridge.install(); // a second install must not double-deliver.
    assertTrue(SLF4JBridgeHandler.isInstalled(), "the bridge stays installed");

    final String message = "idempotent-" + System.nanoTime();
    java.util.logging.Logger.getLogger("com.example.host").info(message);

    final long delivered =
        appender.list.stream().filter(e -> e.getFormattedMessage().equals(message)).count();
    assertEquals(1, delivered, "a record is delivered exactly once, not once per install");
  }
}
