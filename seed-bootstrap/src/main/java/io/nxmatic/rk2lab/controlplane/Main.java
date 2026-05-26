package io.nxmatic.rk2lab.controlplane;

import io.nxmatic.rk2lab.controlplane.pipeline.ApplicationPipeline;

/** Entry point for the Pulumi management-cluster bootstrap program. */
public final class Main {

  private Main() {}

  public static void main(String[] args) {
    ApplicationPipeline.run(
        launch ->
            launch
                .onFailure(
                    (topic, cause) -> SeedLog.error("pipeline", topic + ": " + cause.getMessage()))
                .during(
                    "environment",
                    env ->
                        env.installLogSink()
                            .loadBootstrapConfig()
                            .loadControlplanePolicy()
                            .loadOptions())
                .then()
                .during("bootstrap", bootstrap -> bootstrap.runBootstrapPipeline())
                .then()
                .during("outputs", outputs -> outputs.exportOrPrint())
                .complete());
  }
}
