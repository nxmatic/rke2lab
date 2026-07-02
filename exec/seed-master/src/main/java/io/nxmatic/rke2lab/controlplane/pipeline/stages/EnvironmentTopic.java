package io.nxmatic.rke2lab.controlplane.pipeline.stages;

import com.pulumi.Context;
import io.nxmatic.rke2lab.controlplane.SeedLog;
import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.config.Rke2labConfig;
import io.nxmatic.rke2lab.controlplane.pipeline.BootstrapOptions;
import io.nxmatic.rke2lab.controlplane.policy.ControlplanePolicy;
import java.util.Optional;
import java.util.function.Consumer;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

public final class EnvironmentTopic {

  public interface Sink {
    void logSink(AutoCloseable closeable);

    void bootstrapConfig(BootstrapConfig config);

    void controlplanePolicy(ControlplanePolicy policy);

    void options(BootstrapOptions options);
  }

  private final Optional<Context> pulumiContext;
  private final Sink sink;

  public EnvironmentTopic(Optional<Context> pulumiContext, Sink sink) {
    this.pulumiContext = pulumiContext;
    this.sink = sink;
  }

  public EnvironmentTopic installLogSink() {
    if (pulumiContext.isEmpty()) {
      return this;
    }
    final Context context = pulumiContext.get();
    final AutoCloseable closeable =
        SeedLog.open(
            (event, message) -> {
              switch (event) {
                case ERROR -> context.log().error(message);
                case WARN -> context.log().warn(message);
                case INFO -> context.log().info(message);
                case DEBUG, TRACE -> context.log().debug(message);
              }
            });
    sink.logSink(closeable);
    return this;
  }

  private @MonotonicNonNull Rke2labConfig config;

  /**
   * The root config DTO, read once. Live wraps the Pulumi config; offline (no context) uses
   * defaults so the pipeline can run without operator YAML.
   */
  private Rke2labConfig config() {
    Rke2labConfig resolved = config;
    if (resolved == null) {
      resolved =
          pulumiContext
              .map(context -> Rke2labConfig.from(context.config("rke2lab")))
              .orElseGet(Rke2labConfig::defaults);
      config = resolved;
    }
    return resolved;
  }

  public EnvironmentTopic loadBootstrapConfig() {
    sink.bootstrapConfig(BootstrapConfig.from(config()));
    return this;
  }

  public EnvironmentTopic loadControlplanePolicy() {
    sink.controlplanePolicy(ControlplanePolicy.from(config()));
    return this;
  }

  public EnvironmentTopic loadOptions() {
    sink.options(BootstrapOptions.from(config()));
    return this;
  }

  public Consumer<String> readinessLogger() {
    return message -> SeedLog.info("readiness", message);
  }
}
