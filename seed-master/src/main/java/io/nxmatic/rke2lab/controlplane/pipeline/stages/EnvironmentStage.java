package io.nxmatic.rke2lab.controlplane.pipeline.stages;

import com.pulumi.Context;
import io.nxmatic.rke2lab.controlplane.SeedLog;
import io.nxmatic.rke2lab.controlplane.config.ConfigResolver;
import io.nxmatic.rke2lab.controlplane.incus.BootstrapConfig;
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

  public EnvironmentStage loadBootstrapConfig() {
    final BootstrapConfig.Builder builder = new BootstrapConfig.Builder();
    if (pulumiContext != null) {
      builder.applyConfig(pulumiContext.config("rke2lab"));
    }
    sink.bootstrapConfig(builder.build());
    return this;
  }

  public EnvironmentStage loadControlplanePolicy() {
    sink.controlplanePolicy(
        pulumiContext != null
            ? ControlplanePolicy.from(pulumiContext.config("rke2lab"))
            : ControlplanePolicy.defaults());
    return this;
  }

  public EnvironmentStage loadOptions() {
    if (pulumiContext == null) {
      sink.options(BootstrapOptions.builder().build());
      return this;
    }
    final var config = pulumiContext.config("rke2lab");
    sink.options(
        BootstrapOptions.builder()
            .readinessEnabled(ConfigResolver.resolveReadinessEnabled(config))
            .cleanWorktreeRequired(ConfigResolver.resolveCleanWorktreeRequired(config))
            .bboxFailOnError(ConfigResolver.resolveBboxFailOnError(config))
            .build());
    return this;
  }

  public Consumer<String> readinessLogger() {
    return message -> SeedLog.info("readiness", message);
  }
}
