package io.nxmatic.rke2lab.controlplane.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.annotation.NestedSteps;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.ScenarioStage;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import com.tngtech.jgiven.junit5.JGivenExtension;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.OsgiConnection;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The composing scenario that replaces {@code ClusterSeedPipeline}. Extends {@code
 * ScenarioTestBase} (NOT {@code ScenarioTest}) so we declare the extensions ourselves in order:
 * {@link HostSeeder} FIRST (populates host-facts + connection + hands jGiven the driver's
 * ReportModel before jGiven initializes the scenario), then {@code JGivenExtension}. No {@code
 * Test} suffix on the class → invisible to surefire; played only via the launcher by the driver.
 *
 * <p>The runbook is NOT harvested back: the driver seeds its OWN {@code ReportModel} into the
 * session store, {@link HostSeeder} plants it in jGiven's store so jGiven writes the run into it,
 * and the driver renders from the reference it already holds. Inject-the-model — one owner, no
 * static, no null (the model is created host-side, never absent).
 *
 * <p>The phases run through injected {@link SeedProbes} (live in prod, fakes in tests), fanned out
 * onto the scenario's {@code @ProvidedScenarioState} so each {@code @ScenarioStage} reads the probe
 * it needs — the instance-passing seam that lets the scenario play offline.
 */
@ExtendWith(HostSeeder.class) // ours first: host-facts + connection + the injected model
@ExtendWith(JGivenExtension.class) // jGiven second
public class ClusterSeedScenario
    extends ScenarioTestBase<
        ClusterSeedScenario.Given, ClusterSeedScenario.When, ClusterSeedScenario.Then>
    implements HostSeeder.HostFactsAware,
        HostSeeder.ConnectionAware,
        HostSeeder.ProbesAware,
        HostSeeder.SystemdProbeAware {

  @ProvidedScenarioState HostFacts hostFacts;
  @ProvidedScenarioState OsgiConnection connection;
  @ProvidedScenarioState PreflightProbe preflightProbe;
  @ProvidedScenarioState BboxProbe bboxProbe;
  @ProvidedScenarioState IncusProbe incusProbe;

  /**
   * The optional systemd-adapter probe override — set only when a test seeded it (a fake), so the
   * systemd phase reads it as {@code @ExpectedScenarioState injectedProbe}. Left null in the live
   * boot, where the stage resolves the live probe from the registry.
   */
  @ProvidedScenarioState @MonotonicNonNull SystemdAdapterProbe injectedProbe;

  private final Scenario<Given, When, Then> scenario = createScenario();

  @Override
  public Scenario<Given, When, Then> getScenario() {
    return scenario;
  }

  @Override
  public void acceptHostFacts(HostFacts facts) {
    this.hostFacts = facts;
  }

  @Override
  public void acceptConnection(OsgiConnection connection) {
    this.connection = connection;
  }

  @Override
  public void acceptProbes(SeedProbes probes) {
    this.preflightProbe = probes.preflight();
    this.bboxProbe = probes.bbox();
    this.incusProbe = probes.incus();
  }

  @Override
  public void acceptSystemdProbe(SystemdAdapterProbe probe) {
    this.injectedProbe = probe;
  }

  @Test
  void the_cluster_is_seeded() {
    when().preflight().and().bbox().and().incus().and().systemdAdapter();
  }

  public static class Given extends Stage<Given> {}

  public static class When extends Stage<When> {
    @ScenarioStage PreflightStage preflight;
    @ScenarioStage BboxStage bbox;
    @ScenarioStage IncusStage incus;
    @ScenarioStage SystemdAdapterStage systemdAdapter;

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
  }

  public static class Then extends Stage<Then> {}
}
