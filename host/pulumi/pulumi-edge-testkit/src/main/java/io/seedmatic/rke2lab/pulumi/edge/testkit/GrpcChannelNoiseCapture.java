package io.seedmatic.rke2lab.pulumi.edge.testkit;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Captures the {@code io.grpc} log records a host-space test emits while it drives a real Pulumi
 * inline deployment, so the expected, benign "ManagedChannel was garbage collected without being
 * shut down" SEVERE stack traces don't pollute the test console.
 *
 * <p>This is a HOST-space concern by construction: the noise comes from {@code com.pulumi} / {@code
 * io.grpc} (the channel to the Pulumi engine), which the integration atlas places squarely in host
 * space. It is deliberately NOT routed to the OSGi {@code LogService} — that logger belongs to the
 * pure OSGi/model space, and mapping host-engine noise onto it would conflate the two spaces.
 *
 * <p>Register on a deploying test class with {@code @RegisterExtension}; the captured records are
 * exposed via {@link #records()} for the rare case a test wants to inspect them, but nothing is
 * asserted — the channel's garbage collection is non-deterministic, so the noise's presence is not
 * a contract.
 */
public final class GrpcChannelNoiseCapture implements BeforeEachCallback, AfterEachCallback {

  private final List<LogRecord> captured = new CopyOnWriteArrayList<>();
  private final Logger grpcLogger = Logger.getLogger("io.grpc");

  private Handler installedHandler;
  private boolean previousUseParentHandlers;

  @Override
  public void beforeEach(ExtensionContext context) {
    captured.clear();
    previousUseParentHandlers = grpcLogger.getUseParentHandlers();
    installedHandler =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            captured.add(record);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    installedHandler.setLevel(Level.ALL);
    grpcLogger.addHandler(installedHandler);
    grpcLogger.setUseParentHandlers(false);
  }

  @Override
  public void afterEach(ExtensionContext context) {
    grpcLogger.removeHandler(installedHandler);
    grpcLogger.setUseParentHandlers(previousUseParentHandlers);
    installedHandler = null;
  }

  List<LogRecord> records() {
    return List.copyOf(captured);
  }
}
