package io.nxmatic.rke2lab.netplan.cli.bdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.ScenarioState;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import com.tngtech.jgiven.report.model.ExecutionStatus;
import com.tngtech.jgiven.report.model.ReportModel;
import com.tngtech.jgiven.report.model.ScenarioModel;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.ConnectionReceiver;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.OsgiConnection;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.SeedRuntime;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.CellarReceiver;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioCellar;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioGraft;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.SeedScenario;
import io.nxmatic.rke2lab.seed.bdd.EphemeralCellar;
import io.nxmatic.rke2lab.seed.bdd.SeedReceiver;
import io.nxmatic.rke2lab.seed.bdd.SessionSeed;
import io.nxmatic.rke2lab.seed.bdd.sow.Gardening;
import io.nxmatic.rke2lab.seed.broker.port.Amendment;
import io.nxmatic.rke2lab.seed.broker.port.Cellar;
import io.nxmatic.rke2lab.seed.broker.port.OpaqueCellar;
import java.util.Hashtable;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The netplan-cli root scenario — the host runbook for the {@code yamlExport} verb, spoken in the
 * same gardening register as {@code ClusterSeedScenario} but netplan-only and Pulumi-free. It is a
 * single sow: open the gardening, sow the {@code netplan} coordinate through the broker, reap the
 * runbook. Its side effect is the scion writing {@code blueprint.json} into the SOIL — the host
 * reads that JSON back and converts it to YAML.
 *
 * <p>Why a scenario and not a flat blueprint dump: {@code ClusterNetworkBlueprint} lives in {@code
 * netplan-contract}, a {@code type=contract} bundle — a bundle-realm type the flat host cannot
 * reference (the realm-boundary law forbids it; the old flat {@code BlueprintExportCommand} {@code
 * NoClassDefFoundError}ed). Sowing through the broker (the ONE system-exported {@code
 * seed.broker.port} membrane) grows {@code NetplanBlueprintScenario} in-container, where the
 * blueprint type is reachable; only host-neutral JSON crosses back.
 */
@SeedScenario
@SeedRuntime
public class NetplanCliScenario
    extends ScenarioTestBase<
        NetplanCliScenario.Given, NetplanCliScenario.When, NetplanCliScenario.Then>
    implements SeedReceiver<NetplanCliRun>, ConnectionReceiver, CellarReceiver<ScenarioCellar> {

  /** The inbound channel the CLI seeds the {@link NetplanCliRun} through; single-sourced. */
  @RegisterExtension
  public static final SessionSeed<NetplanCliRun> SEED =
      new SessionSeed<>(NetplanCliRun.class, "netplan-cli-run");

  private final Scenario<Given, When, Then> scenario = createScenario();

  @MonotonicNonNull private NetplanCliRun run;
  @MonotonicNonNull private OsgiConnection connection;
  @MonotonicNonNull private ScenarioCellar cellar;

  @Override
  public Scenario<Given, When, Then> getScenario() {
    return scenario;
  }

  @Override
  public void receiveSeed(NetplanCliRun run) {
    this.run = run;
  }

  @Override
  public void receiveConnection(OsgiConnection connection) {
    this.connection = connection;
  }

  @Override
  public void receiveCellar(ScenarioCellar cellar) {
    this.cellar = cellar;
  }

  @Test
  void the_blueprint_is_exported() {
    final NetplanCliRun seedRun =
        Objects.requireNonNull(run, "the NetplanCliRun was not seeded before the scenario ran");
    final OsgiConnection world =
        Objects.requireNonNull(
            connection, "the OsgiConnection was not received before the scenario ran");
    final ScenarioCellar tx =
        Objects.requireNonNull(
            cellar, "the ScenarioCellar was not injected before the scenario ran");
    given().i_have_access_to_the_open_gardening(seedRun, world, tx);
    when().the_blueprint_is_sown();
    then().the_runbook_is_reaped();
  }

  /**
   * The GIVEN opens the gardening over the world extension's connection and holds the run's soil.
   */
  public static class Given extends Stage<Given> {

    @ProvidedScenarioState Gardening gardening;
    @ProvidedScenarioState Cellar cellar;
    @ProvidedScenarioState Optional<String> materializationRoot;

    public Given i_have_access_to_the_open_gardening(
        @Hidden NetplanCliRun run, @Hidden OsgiConnection world, @Hidden ScenarioCellar cellar) {
      // Open OVER the connection the world extension owns (class scope) — no second Felix booted.
      this.gardening = Gardening.over(world);
      this.cellar = cellar;
      this.materializationRoot = run.materializationRoot();
      // Publish the run's durable backend for the ROOT drain ScenarioCellarExtension performs at
      // the
      // end. netplan-cli is a standalone export with no persistent commissioner (no Pulumi), so the
      // backend is the offline EphemeralCellar — the scion persists nothing to a cellar; its
      // harvest
      // is the materialised blueprint.json.
      world.context().registerService(OpaqueCellar.class, new EphemeralCellar(), new Hashtable<>());
      return self();
    }
  }

  /** The WHEN sows the netplan coordinate through the broker and reaps its runbook. */
  public static class When extends Stage<When> {

    @ScenarioState Gardening gardening;
    @ScenarioState Cellar cellar;
    @ScenarioState Optional<String> materializationRoot;
    @ProvidedScenarioState String runbook;

    @As("the blueprint is sown")
    public When the_blueprint_is_sown() {
      // The only amendment the CLI carries is the SOIL — the plot the scion writes blueprint.json
      // into. The rest of NetplanRunbookInput falls to the scion's defaults() at the reconcile
      // door.
      final Map<String, JsonNode> amendments =
          materializationRoot
              .map(root -> Map.<String, JsonNode>of(Amendment.SOIL, TextNode.valueOf(root)))
              .orElseGet(Map::of);
      this.runbook = gardening.sow("netplan", amendments, cellar);
      return self();
    }
  }

  /** The THEN asserts the scion reaped a runbook — the sow grew the in-container export. */
  public static class Then extends Stage<Then> {

    @ScenarioState String runbook;

    @As("the runbook is reaped")
    public Then the_runbook_is_reaped() {
      if (runbook == null || runbook.isBlank()) {
        throw new AssertionError("the netplan sow reaped no runbook — the scion did not grow");
      }
      // A non-blank runbook is not enough: a FAILED in-container export still reaps its runbook
      // JSON. Rebuild it and fail the CLI on a FAILED scion — there is no host tree to graft into
      // here — carrying the scion's own error text; otherwise the CLI exits GREEN on a failed sow.
      final ReportModel model = new ScenarioGraft().rebuild(runbook);
      if (model.getScenarios().isEmpty()) {
        throw new AssertionError("the netplan sow reaped a runbook with no scenario");
      }
      final ScenarioModel scenario = model.getScenarios().get(0);
      if (scenario.getExecutionStatus() == ExecutionStatus.FAILED) {
        throw new AssertionError(
            "the netplan export failed in-container: "
                + scenario.getScenarioCases().get(0).getErrorMessage());
      }
      return self();
    }
  }
}
