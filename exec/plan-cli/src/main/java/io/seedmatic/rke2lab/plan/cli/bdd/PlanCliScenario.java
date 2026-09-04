package io.seedmatic.rke2lab.plan.cli.bdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.ScenarioState;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.ConnectionReceiver;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.OsgiConnection;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.SeedRuntime;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.CellarReceiver;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioCellar;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioGraft;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.SeedScenario;
import io.seedmatic.rke2lab.seed.bdd.EphemeralCellar;
import io.seedmatic.rke2lab.seed.bdd.SeedReceiver;
import io.seedmatic.rke2lab.seed.bdd.SessionSeed;
import io.seedmatic.rke2lab.seed.bdd.sow.Gardening;
import io.seedmatic.rke2lab.seed.broker.port.Amendment;
import io.seedmatic.rke2lab.seed.broker.port.Cellar;
import io.seedmatic.rke2lab.seed.broker.port.OpaqueCellar;
import java.util.Hashtable;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The plan-cli root scenario — the host runbook for the {@code plan <plane> export} verbs, spoken
 * in the same gardening register as {@code ClusterSeedScenario} but plan-only and Pulumi-free. It
 * is a single sow, parameterised by the {@link io.seedmatic.rke2lab.plan.cli.Plane} the run
 * carries: open the gardening, sow the plane's coordinate through the broker, reap the runbook. Its
 * side effect is the domain scion writing its export file into the SOIL — the host reads that file
 * back and renders it (YAML/JSON per plane).
 *
 * <p>Why a scenario and not a flat dump: each plane's export type ({@code ClusterNetworkBlueprint},
 * {@code DataplanLayout}) lives in a {@code type=contract} bundle — a bundle-realm type the flat
 * host cannot reference (the realm-boundary law forbids it). Sowing through the broker (the ONE
 * system-exported {@code seed.broker.port} membrane) grows the domain scion in-container, where the
 * type is reachable; only host-neutral JSON crosses back.
 */
@SeedScenario
@SeedRuntime
public class PlanCliScenario
    extends ScenarioTestBase<PlanCliScenario.Given, PlanCliScenario.When, PlanCliScenario.Then>
    implements SeedReceiver<PlanCliRun>, ConnectionReceiver, CellarReceiver<ScenarioCellar> {

  /** The inbound channel the CLI seeds the {@link PlanCliRun} through; single-sourced. */
  @RegisterExtension
  public static final SessionSeed<PlanCliRun> SEED =
      new SessionSeed<>(PlanCliRun.class, "plan-cli-run");

  private final Scenario<Given, When, Then> scenario = createScenario();

  @MonotonicNonNull private PlanCliRun run;
  @MonotonicNonNull private OsgiConnection connection;
  @MonotonicNonNull private ScenarioCellar cellar;

  @Override
  public Scenario<Given, When, Then> getScenario() {
    return scenario;
  }

  @Override
  public void receiveSeed(PlanCliRun run) {
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
  void the_plan_is_exported() {
    final PlanCliRun seedRun =
        Objects.requireNonNull(run, "the PlanCliRun was not seeded before the scenario ran");
    final OsgiConnection world =
        Objects.requireNonNull(
            connection, "the OsgiConnection was not received before the scenario ran");
    final ScenarioCellar tx =
        Objects.requireNonNull(
            cellar, "the ScenarioCellar was not injected before the scenario ran");
    given().i_have_access_to_the_open_gardening(seedRun, world, tx);
    when().the_plan_is_sown();
    then().the_runbook_is_reaped();
  }

  /**
   * The GIVEN opens the gardening over the world extension's connection and holds the run's soil +
   * the plane's coordinate.
   */
  public static class Given extends Stage<Given> {

    @ProvidedScenarioState Gardening gardening;
    @ProvidedScenarioState Cellar cellar;
    @ProvidedScenarioState Optional<String> materializationRoot;
    @ProvidedScenarioState String coordinate;

    public Given i_have_access_to_the_open_gardening(
        @Hidden PlanCliRun run, @Hidden OsgiConnection world, @Hidden ScenarioCellar cellar) {
      // Open OVER the connection the world extension owns (class scope) — no second Felix booted.
      this.gardening = Gardening.over(world);
      this.cellar = cellar;
      this.materializationRoot = run.materializationRoot();
      this.coordinate = run.plane().coordinate();
      // Publish the run's durable backend for the ROOT drain ScenarioCellarExtension performs at
      // the
      // end. plan-cli is a standalone export with no persistent commissioner (no Pulumi), so the
      // backend is the offline EphemeralCellar — the scion persists nothing to a cellar; its
      // harvest
      // is the materialised export file.
      world.context().registerService(OpaqueCellar.class, new EphemeralCellar(), new Hashtable<>());
      return self();
    }
  }

  /** The WHEN sows the plane's coordinate through the broker and reaps its runbook. */
  public static class When extends Stage<When> {

    @ScenarioState Gardening gardening;
    @ScenarioState Cellar cellar;
    @ScenarioState Optional<String> materializationRoot;
    @ScenarioState String coordinate;
    @ProvidedScenarioState String runbook;

    @As("the plan is sown")
    public When the_plan_is_sown() {
      // The only amendment the CLI carries is the SOIL — the plot the scion writes its export into,
      // and the runbook input's only component (an Optional; absent → the scion's temp dir). The
      // export itself is produced in-container, so there is nothing else to sow.
      final Map<String, JsonNode> amendments =
          materializationRoot
              .map(root -> Map.<String, JsonNode>of(Amendment.SOIL, TextNode.valueOf(root)))
              .orElseGet(Map::of);
      this.runbook = gardening.sow(coordinate, amendments, cellar);
      return self();
    }
  }

  /** The THEN asserts the scion reaped a runbook — the sow grew the in-container export. */
  public static class Then extends Stage<Then> {

    @ScenarioState String runbook;

    @As("the runbook is reaped")
    public Then the_runbook_is_reaped() {
      if (runbook == null || runbook.isBlank()) {
        throw new AssertionError("the plan sow reaped no runbook — the scion did not grow");
      }
      // A non-blank runbook is not enough: a FAILED in-container export still reaps its runbook.
      // This
      // CLI grafts into no host tree, so it asserts the scion passed here — the assert throws the
      // scion's own reason (message + stack) on a FAILED sow, else the CLI exits GREEN on it.
      new ScenarioGraft().assertPassed(runbook, "the plan export");
      return self();
    }
  }
}
