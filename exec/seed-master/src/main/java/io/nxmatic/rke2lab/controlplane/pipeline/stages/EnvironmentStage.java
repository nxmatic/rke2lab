package io.nxmatic.rke2lab.controlplane.pipeline.stages;

import com.pulumi.Context;
import io.nxmatic.rke2lab.controlplane.SeedLog;
import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.config.Rke2labConfig;
import io.nxmatic.rke2lab.controlplane.pipeline.BootstrapOptions;
import io.nxmatic.rke2lab.controlplane.policy.ControlplanePolicy;
import java.util.function.Consumer;

public final class EnvironmentStage {

  public interface Sink {
    void logSink(AutoCloseable closeable);

    void bootstrapConfig(BootstrapConfig config);

    void controlplanePolicy(ControlplanePolicy policy);

    void options(BootstrapOptions options);
  }

  private final Context pulumiContext;
  private final Sink sink;

  public EnvironmentStage(Context pulumiContext, Sink sink) {
    this.pulumiContext = pulumiContext;
    this.sink = sink;
  }

  public EnvironmentStage installLogSink() {
    if (pulumiContext == null) {
      return this;
    }
    final AutoCloseable closeable =
        SeedLog.installPulumiLogSink(
            (event, message) -> {
              switch (event) {
                case ERROR -> pulumiContext.log().error(message);
                case WARN -> pulumiContext.log().warn(message);
                case INFO -> pulumiContext.log().info(message);
                case DEBUG, TRACE -> pulumiContext.log().debug(message);
              }
            });
    sink.logSink(closeable);
    return this;
  }

  private Rke2labConfig config;

  /**
   * The root config DTO, read once. Live wraps the Pulumi config; offline (no context) uses
   * defaults so the pipeline can run without operator YAML.
   */
  private Rke2labConfig config() {
    if (config == null) {
      config =
          pulumiContext != null
              ? Rke2labConfig.from(pulumiContext.config("rke2lab"))
              : Rke2labConfig.defaults();
    }
    return config;
  }

  public EnvironmentStage loadBootstrapConfig() {
    sink.bootstrapConfig(BootstrapConfig.from(config()));
    return this;
  }

  public EnvironmentStage loadControlplanePolicy() {
    sink.controlplanePolicy(ControlplanePolicy.from(config()));
    return this;
  }

  public EnvironmentStage loadOptions() {
    sink.options(BootstrapOptions.from(config()));
    return this;
  }

  public Consumer<String> readinessLogger() {
    return message -> SeedLog.info("readiness", message);
  }
}
