package io.nxmatic.rke2lab.controlplane.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.ScenarioState.Resolution;
import io.nxmatic.rke2lab.controlplane.bbox.BboxReconciliationOrchestrator.ReconciliationResult;
import io.nxmatic.rke2lab.controlplane.incus.IncusResourceBootstrap.BootstrapResult;
import io.nxmatic.rke2lab.controlplane.resources.ResourceManager.ResourceCreationResult;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Terminal projection: READS the value-DAG and calls the same {@link
 * io.nxmatic.rke2lab.controlplane.pipeline.OutputBuilder#buildOutputs} the fluent {@code
 * collectOutputs()} used — the single read of the DAG into the outputs Map, no parallel
 * accumulator. The incus {@code bootstrap} arrives as an Outcome ({@link Optional}); the outputs
 * projection needs the materialized result, so it is unwrapped here (a run that reached the outputs
 * phase provisioned an instance).
 */
public class OutputsStage extends Stage<OutputsStage> {

  @ExpectedScenarioState HostFacts hostFacts;

  @ExpectedScenarioState @MonotonicNonNull ReconciliationResult bbox;
  @ExpectedScenarioState @MonotonicNonNull Optional<BootstrapResult> bootstrap;

  @ExpectedScenarioState(resolution = Resolution.NAME)
  @MonotonicNonNull
  Map<String, Object> adapterLaunch;

  @ExpectedScenarioState @MonotonicNonNull ResourceCreationResult resources;

  @ProvidedScenarioState(resolution = Resolution.NAME)
  @MonotonicNonNull
  Map<String, Object> outputs;

  /**
   * The driver's outputs sink — {@link Optional#empty()} in a focused test that ignores outputs,
   * present when a driver launches for real. This terminal stage OWNS the publication:
   * {@code @AfterScenario} on the scenario instance never fires (jGiven runs it only on stages), so
   * the collected map is pushed here, at the end of the last phase the driver harvests.
   */
  @ExpectedScenarioState(resolution = Resolution.NAME)
  Optional<AtomicReference<Map<String, Object>>> outputsSink = Optional.empty();

  @As("the stack outputs are collected")
  public OutputsStage the_stack_outputs_are_collected() {
    final ReconciliationResult reconciliation =
        Objects.requireNonNull(bbox, "bbox (bbox phase not run)");
    final BootstrapResult bootstrapResult =
        Objects.requireNonNull(bootstrap, "bootstrap (incus phase not run)")
            .orElseThrow(
                () -> new IllegalStateException("outputs need the incus bootstrap result"));
    final Map<String, Object> adapter =
        Objects.requireNonNull(adapterLaunch, "adapterLaunch (systemd phase not run)");
    final ResourceCreationResult resourceResult =
        Objects.requireNonNull(resources, "resources (resources phase not run)");

    final Map<String, Object> collected =
        hostFacts
            .outputBuilder()
            .buildOutputs(
                hostFacts.config(),
                hostFacts.policy(),
                bootstrapResult,
                reconciliation,
                adapter,
                resourceResult);
    this.outputs = collected;
    outputsSink.ifPresent(sink -> sink.set(collected));
    return self();
  }
}
