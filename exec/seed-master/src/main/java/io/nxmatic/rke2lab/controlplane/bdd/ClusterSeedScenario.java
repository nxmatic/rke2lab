package io.nxmatic.rke2lab.controlplane.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.annotation.NestedSteps;
import com.tngtech.jgiven.annotation.ScenarioStage;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import com.tngtech.jgiven.junit5.JGivenExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The composing scenario that replaces {@code ClusterSeedPipeline}. Extends {@code
 * ScenarioTestBase} (NOT {@code ScenarioTest}) so we declare the extensions ourselves in order:
 * {@link HostSeeder} FIRST, then {@code JGivenExtension}. No {@code Test} suffix on the class →
 * invisible to surefire; played only via the launcher by the driver.
 *
 * <p>This class carries NO state and no {@code accept*} channel. {@link HostSeeder} pushes a {@link
 * StageContext} straight into the run's value-DAG ({@code ScenarioExecutor.readScenarioState}), so
 * every stage resolves its {@code @ExpectedScenarioState} the one way jGiven flows anything — the
 * DAG. The scenario's only job is composition: the phase order and the nested sub-trees.
 *
 * <p>The runbook is NOT harvested back either: the driver seeds its OWN {@code ReportModel} into
 * the session store, {@link HostSeeder} plants it in jGiven's store so jGiven writes the run into
 * it, and the driver renders from the reference it already holds. The outputs are published by the
 * terminal {@link OutputsStage} into the driver's sink (jGiven runs {@code @AfterScenario} on
 * stages only, never on this instance).
 */
@ExtendWith(
    HostSeeder.class) // ours first: seeds the StageContext into the DAG + the injected model
@ExtendWith(JGivenExtension.class) // jGiven second
public class ClusterSeedScenario
    extends ScenarioTestBase<
        ClusterSeedScenario.Given, ClusterSeedScenario.When, ClusterSeedScenario.Then> {

  private final Scenario<Given, When, Then> scenario = createScenario();

  @Override
  public Scenario<Given, When, Then> getScenario() {
    return scenario;
  }

  @Test
  void the_cluster_is_seeded() {
    when()
        .preflight()
        .and()
        .bbox()
        .and()
        .incus()
        .and()
        .systemdAdapter()
        .and()
        .resources()
        .and()
        .outputs();
  }

  public static class Given extends Stage<Given> {}

  public static class When extends Stage<When> {
    @ScenarioStage PreflightStage preflight;
    @ScenarioStage BboxStage bbox;
    @ScenarioStage IncusStage incus;
    @ScenarioStage SystemdAdapterStage systemdAdapter;
    @ScenarioStage ResourcesStage resources;
    @ScenarioStage OutputsStage outputs;

    @NestedSteps
    @As("preflight")
    public When preflight() {
      preflight.the_preflight_gates_are_enforced();
      return self();
    }

    @NestedSteps
    @As("bbox")
    public When bbox() {
      bbox.the_bbox_reservations_are_reconciled();
      return self();
    }

    @NestedSteps
    @As("incus")
    public When incus() {
      incus.the_incus_instance_is_provisioned();
      return self();
    }

    @NestedSteps
    @As("systemd adapter")
    public When systemdAdapter() {
      systemdAdapter.the_systemd_adapter_is_launched();
      return self();
    }

    @NestedSteps
    @As("resources")
    public When resources() {
      resources.the_bootstrap_resources_are_created();
      return self();
    }

    @NestedSteps
    @As("outputs")
    public When outputs() {
      outputs.the_stack_outputs_are_collected();
      return self();
    }
  }

  public static class Then extends Stage<Then> {}
}
