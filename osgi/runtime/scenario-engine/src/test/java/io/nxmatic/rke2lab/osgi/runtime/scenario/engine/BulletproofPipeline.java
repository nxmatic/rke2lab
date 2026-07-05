package io.nxmatic.rke2lab.osgi.runtime.scenario.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.annotation.NestedSteps;
import com.tngtech.jgiven.annotation.ScenarioStage;
import com.tngtech.jgiven.impl.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Store;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

/**
 * The committed acceptance walking-skeleton (plan Task 7): a two-step jGiven scenario played
 * end-to-end through the WHOLE socle — {@link JUnitLauncherCore} discovers it, {@code @SeedRuntime}
 * opens a live world and holds it at the bundle level, the scenario yields a jGiven {@code
 * ReportModel}. The steps are placeholders (no real system touch); the point is the SEAMS, exactly
 * what increment 2's real {@code SeedScenario} reuses with real phases substituted in.
 *
 * <p>NO {@code Test} suffix on the class — surefire selects by class NAME, so this is invisible to
 * it; it is played ONLY when {@link SoclePipelineTest} discovery-selects it through the launcher.
 * The two worlds never cross.
 *
 * <p>The scenario is played STANDALONE ({@code Scenario.create + setModel}) into the {@code
 * ReportModel} the driver injected via {@link LaunchedPipelineExchange} — the same inject-the-model
 * idiom the prod checkpoints use, so no runbook is captured back: the driver reads its own
 * reference. {@link ConnectionSeeder} places the exchange's connection into the discipline's {@code
 * Store} BEFORE {@code @SeedRuntime}'s {@code BaseWorldExtension.beforeAll} runs, so the discipline
 * connects to the driver's testkit-booted world instead of self-booting one (the engine test module
 * stages no bundles for {@code embedded()}).
 */
@ExtendWith(BulletproofPipeline.ConnectionSeeder.class)
@SeedRuntime
class BulletproofPipeline {

  /**
   * Seeds the discipline's {@code Store} with the exchange's connection before {@code @SeedRuntime}
   * connects. Declared as a class-level {@code @ExtendWith} BEFORE {@code @SeedRuntime}: class
   * extensions run their {@code beforeAll} in declaration order, so this wins over the discipline's
   * meta-{@code @ExtendWith} and {@code BaseWorldExtension} finds a connection already present
   * instead of opening {@code embedded()} (a field {@code @RegisterExtension} would run too LATE —
   * after every class extension — which is the ordering trap {@code ExtensionDisciplineTest}
   * documents).
   */
  static final class ConnectionSeeder implements BeforeAllCallback {
    @Override
    public void beforeAll(ExtensionContext context) {
      final Store store = context.getStore(BaseWorldExtension.NAMESPACE);
      store.put(BaseWorldExtension.CONNECTION, LaunchedPipelineExchange.current().connection());
    }
  }

  @Test
  void the_seed_connects_and_reconciles_two_placeholder_units() {
    final Scenario<Given, WhenTheWorldIsConnected, Then> scenario =
        Scenario.create(Given.class, WhenTheWorldIsConnected.class, Then.class);
    scenario.setModel(LaunchedPipelineExchange.current().runbook());
    scenario.startScenario("the seed connects and reconciles two placeholder units");

    scenario.given().the_osgi_world_is_connected();
    scenario.when().two_placeholder_units_are_reconciled();

    try {
      scenario.finished();
    } catch (Throwable neverThrownGreen) {
      throw new AssertionError("the bulletproof pipeline does not fail green", neverThrownGreen);
    }
  }

  /**
   * The connect step — the first step of every seed scenario ("the OSGi world is connected"). It
   * reads the connection the discipline opened (via the exchange) and asserts the world is LIVE:
   * the system bundle (id 0) ACTIVE. Increment 2's seed opens {@code embedded()} here instead.
   */
  static class Given extends Stage<Given> {

    @As("the OSGi world is connected")
    public Given the_osgi_world_is_connected() {
      final BundleContext context = LaunchedPipelineExchange.current().connection().context();
      assertEquals(
          Bundle.ACTIVE,
          context.getBundle(0).getState(),
          "the connect step observed a LIVE embedded framework (system bundle ACTIVE)");
      return self();
    }
  }

  /**
   * The reconcile step — a {@code @NestedSteps} parent whose body opens two placeholder sub-steps,
   * so the runbook carries a two-tier tree (the shape a real phase with sub-checks renders). No
   * real system is touched.
   */
  static class WhenTheWorldIsConnected extends Stage<WhenTheWorldIsConnected> {

    @ScenarioStage PlaceholderUnits units;

    @NestedSteps
    @As("two placeholder units are reconciled")
    public WhenTheWorldIsConnected two_placeholder_units_are_reconciled() {
      units.the_first_unit_is_reconciled().the_second_unit_is_reconciled();
      return self();
    }
  }

  /** The two placeholder sub-steps that render nested under the reconcile step. */
  static class PlaceholderUnits extends Stage<PlaceholderUnits> {

    @As("the first unit is reconciled")
    public PlaceholderUnits the_first_unit_is_reconciled() {
      return self();
    }

    @As("the second unit is reconciled")
    public PlaceholderUnits the_second_unit_is_reconciled() {
      return self();
    }
  }

  /**
   * The THEN stage — no assertions of its own (the connect + reconcile steps carry the scenario).
   * It exists because jGiven's {@code Scenario.create} instantiates all three stage types upfront,
   * so the type slot must be a real {@code Stage}, not {@code Object}.
   */
  static class Then extends Stage<Then> {}
}
