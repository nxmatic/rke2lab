package io.seedmatic.rke2lab.osgibench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.junit.testkit.OsgiWorld;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.FrameworkProperty;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.OutOfContainerFrameworkExtension;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

/**
 * Proves the INVERTED logging flow the host boot relies on: the host emits on the JDK {@code
 * java.util.logging} bus, and pax-logging-api's {@code JdkHandler} (installed on the root JUL
 * logger by default — its boot logs "Enabling Java Util Logging API support") captures it into
 * pax-logging-logback, the single sink. So a plain {@code java.util.logging} record — with NO OSGi
 * bundle involved — must land in pax's logback, configured here FILE-only via Configuration Admin,
 * and NOTHING must reach the console (no ConsoleAppender: the write that wedged the boot under a
 * remote debugger is gone).
 *
 * <p>This is the mechanism the host side will lean on once its slf4j binding is flipped to {@code
 * slf4j-jdk14}: everything host converges on JUL, pax drains JUL into its logback.
 */
@OsgiWorld
@FrameworkProperty(name = "org.ops4j.pax.logging.service.frameworkEventsLogLevel", value = "WARN")
// Silence the pax-logging-api fallback logger (its "Enabling … API support" lines go to the default
// console-ish stream) so the console-empty assertion reflects only the backend.
@FrameworkProperty(name = "org.ops4j.pax.logging.DefaultServiceLog.level", value = "ERROR")
class PaxLoggingJulCaptureTest {

  @RegisterExtension
  static final OutOfContainerFrameworkExtension felix =
      OutOfContainerFrameworkExtension.builder().withoutScr().withoutResolver().build();

  @Test
  void jdkJavaUtilLoggingIsCapturedByPaxAndNeverHitsTheConsole() throws Exception {
    final Path sink = Files.createTempFile("pax-jul-sink-", ".log");
    final Path logbackConfig = writeFileOnlyLogbackConfig(sink);
    final BundleContext ctx = felix.context();

    final PrintStream realOut = System.out;
    final ByteArrayOutputStream console = new ByteArrayOutputStream();
    System.setOut(new PrintStream(console, true, StandardCharsets.UTF_8));

    final String marker = "jul-capture-marker-" + UUID.randomUUID();
    try {
      // Configuration Admin FIRST, seed pax's PID with a FILE-only logback (no console), THEN start
      // pax so it configures from it. pax-logging-api installs its JdkHandler on activation.
      installAndStart(ctx, "org.apache.felix.configadmin");
      seedPaxLoggingConfig(ctx, logbackConfig);
      installAndStart(ctx, "org.ops4j.pax.logging.pax-logging-api");
      installAndStart(ctx, "org.ops4j.pax.logging.pax-logging-logback");

      // A plain JDK java.util.logging record — the host bus. pax's JdkHandler must drain it.
      final Logger jul = Logger.getLogger("io.seedmatic.host.probe");
      jul.log(Level.SEVERE, marker);

      assertTrue(
          awaitFileContains(sink, marker),
          "java.util.logging must be captured into pax's logback sink (file: "
              + Files.readString(sink)
              + ")");
    } finally {
      System.setOut(realOut);
    }

    assertEquals(
        "",
        console.toString(StandardCharsets.UTF_8),
        "pax's logback must not write to the console (a ConsoleAppender):\n" + console);
  }

  private static boolean awaitFileContains(Path file, String needle) throws Exception {
    final long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
    do {
      if (Files.exists(file) && Files.readString(file).contains(needle)) {
        return true;
      }
      Thread.sleep(50);
    } while (System.nanoTime() < deadline);
    return false;
  }

  private static Bundle installAndStart(BundleContext ctx, String symbolicName) throws Exception {
    final Bundle bundle = felix.install(symbolicName);
    bundle.start();
    return bundle;
  }

  /**
   * Seed the {@code org.ops4j.pax.logging} PID with a FILE-only logback config, by reflection to
   * avoid a compile dependency on (and classloader clash over) {@code org.osgi.service.cm}.
   */
  private static void seedPaxLoggingConfig(BundleContext ctx, Path logbackConfig) throws Exception {
    final ServiceReference<?>[] refs =
        ctx.getAllServiceReferences("org.osgi.service.cm.ConfigurationAdmin", null);
    if (refs == null || refs.length == 0) {
      throw new IllegalStateException("ConfigurationAdmin not registered");
    }
    final Object cm = ctx.getService(refs[0]);
    final Object configuration =
        cm.getClass()
            .getMethod("getConfiguration", String.class, String.class)
            .invoke(cm, "org.ops4j.pax.logging", null);
    final Dictionary<String, Object> props = new Hashtable<>();
    props.put("org.ops4j.pax.logging.logback.config.file", logbackConfig.toString());
    configuration.getClass().getMethod("update", Dictionary.class).invoke(configuration, props);
  }

  private static Path writeFileOnlyLogbackConfig(Path sink) throws Exception {
    final Path xml = Files.createTempFile("pax-logback-", ".xml");
    Files.writeString(
        xml,
        """
        <configuration>
          <appender name="FILE" class="ch.qos.logback.core.FileAppender">
            <file>__SINK__</file>
            <append>false</append>
            <immediateFlush>true</immediateFlush>
            <encoder><pattern>%msg%n</pattern></encoder>
          </appender>
          <root level="TRACE">
            <appender-ref ref="FILE"/>
          </root>
        </configuration>
        """
            .replace("__SINK__", sink.toString()));
    return xml;
  }
}
