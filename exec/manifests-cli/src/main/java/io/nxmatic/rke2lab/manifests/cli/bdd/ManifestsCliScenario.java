package io.nxmatic.rke2lab.manifests.cli.bdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.ScenarioState;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
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
 * The manifests-cli root scenario — the host runbook for the {@code synthesize} verb, spoken in the
 * same gardening register as {@code ClusterSeedScenario} but manifests-only and Pulumi-free. It is
 * a single sow: open the gardening, sow the {@code manifests} coordinate through the broker, reap
 * the runbook.
 *
 * <p>Why a scenario and not a bare {@code broker.sow} from {@code Main}: the sow rides the run's
 * transactional {@link ScenarioCellar}, which only {@code ScenarioCellarExtension} can inject
 * (there is no host-usable {@link Cellar} outside a scenario — the other impls are testkit). And
 * crucially it is why the CLI can reach the synthesis at all: {@code ManifestSynthesisService} is
 * registered with the manifests-core bundle's copy of the (non-seam) {@code manifests-contract}
 * class, so a host-side {@code awaitService(ManifestSynthesisService.class)} on the flat copy never
 * matches — the OLD boot+awaitService CLI was structurally broken. Sowing through the broker (the
 * ONE system-exported {@code seed.broker.port} membrane) grows {@code ManifestSynthesisScenario}
 * in-container, where its {@code @OsgiService} resolves the service bundle-side.
 */
@SeedScenario
@SeedRuntime
public class ManifestsCliScenario
    extends ScenarioTestBase<
        ManifestsCliScenario.Given, ManifestsCliScenario.When, ManifestsCliScenario.Then>
    implements SeedReceiver<ManifestsCliRun>, ConnectionReceiver, CellarReceiver<ScenarioCellar> {

  /** The inbound channel {@code Main} seeds the {@link ManifestsCliRun} through; single-sourced. */
  @RegisterExtension
  public static final SessionSeed<ManifestsCliRun> SEED =
      new SessionSeed<>(ManifestsCliRun.class, "manifests-cli-run");

  private final Scenario<Given, When, Then> scenario = createScenario();

  @MonotonicNonNull private ManifestsCliRun run;
  @MonotonicNonNull private OsgiConnection connection;
  @MonotonicNonNull private ScenarioCellar cellar;

  @Override
  public Scenario<Given, When, Then> getScenario() {
    return scenario;
  }

  @Override
  public void receiveSeed(ManifestsCliRun run) {
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
  void the_manifests_are_synthesized() {
    final ManifestsCliRun seedRun =
        Objects.requireNonNull(run, "the ManifestsCliRun was not seeded before the scenario ran");
    final OsgiConnection world =
        Objects.requireNonNull(
            connection, "the OsgiConnection was not received before the scenario ran");
    final ScenarioCellar tx =
        Objects.requireNonNull(
            cellar, "the ScenarioCellar was not injected before the scenario ran");
    given().i_have_access_to_the_open_gardening(seedRun, world, tx);
    when().the_manifests_are_sown();
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
        @Hidden ManifestsCliRun run, @Hidden OsgiConnection world, @Hidden ScenarioCellar cellar) {
      // Open OVER the connection the world extension owns (class scope) — no second Felix booted;
      // the
      // extension closes it at afterAll.
      this.gardening = Gardening.over(world);
      this.cellar = cellar;
      this.materializationRoot = run.materializationRoot();
      // Publish the run's durable backend for the ROOT drain ScenarioCellarExtension performs at
      // the
      // end. manifests-cli is a standalone synthesis with no persistent commissioner (no Pulumi),
      // so
      // the backend is the offline EphemeralCellar — the transactional cellar served reads during
      // the
      // run, and its end-of-run drain lands here and is discarded (the scion persists nothing to a
      // cellar; its harvest is the materialised tree).
      world.context().registerService(OpaqueCellar.class, new EphemeralCellar(), new Hashtable<>());
      return self();
    }
  }

  /** The WHEN sows the manifests coordinate through the broker and reaps its runbook. */
  public static class When extends Stage<When> {

    @ScenarioState Gardening gardening;
    @ScenarioState Cellar cellar;
    @ScenarioState Optional<String> materializationRoot;
    @ProvidedScenarioState String runbook;

    @As("the manifests are sown")
    public When the_manifests_are_sown() {
      // The only amendment the CLI carries is the SOIL — the plot to materialise into. The rest of
      // ManifestsRunbookInput (which layers publish, which debug) falls to the scion's defaults()
      // at
      // the reconcile door. An empty soil (no -Drke2lab.manifests.outdir) sows an empty trigger;
      // the
      // scion materialises into a temp dir (a bare survey).
      final Map<String, JsonNode> amendments =
          materializationRoot
              .map(root -> Map.<String, JsonNode>of(Amendment.SOIL, TextNode.valueOf(root)))
              .orElseGet(Map::of);
      this.runbook = gardening.sow("manifests", amendments, cellar);
      return self();
    }
  }

  /** The THEN asserts the scion reaped a runbook — the sow grew the in-container synthesis. */
  public static class Then extends Stage<Then> {

    @ScenarioState String runbook;

    @As("the runbook is reaped")
    public Then the_runbook_is_reaped() {
      if (runbook == null || runbook.isBlank()) {
        throw new AssertionError("the manifests sow reaped no runbook — the scion did not grow");
      }
      // A non-blank runbook is not enough: a FAILED in-container synthesis still reaps its runbook.
      // This CLI grafts into no host tree, so it asserts the scion passed here — the assert throws
      // the scion's own reason (message + stack) on a FAILED sow, else the CLI exits GREEN on it.
      new ScenarioGraft().assertPassed(runbook, "the manifests synthesis");
      return self();
    }
  }
}
