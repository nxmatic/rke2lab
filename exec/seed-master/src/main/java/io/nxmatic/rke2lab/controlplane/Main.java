package io.nxmatic.rke2lab.controlplane;

import io.nxmatic.rke2lab.controlplane.pipeline.ApplicationPipeline;

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
                .during("cluster seed", seed -> seed.seedCluster())
                .then()
                .during("outputs", outputs -> outputs.exportOrPrint())
                .complete());
  }
}
