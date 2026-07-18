package io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container;

import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.support.store.Namespace;
import org.junit.platform.engine.support.store.NamespacedHierarchicalStore;

/**
 * The one recipe that plays a {@code @SeedScenario} in-container and harvests its {@link
 * ScenarioOutcome} — the shared body the five per-domain {@code *BddScenarios} front-doors were
 * each copying verbatim (build a {@link JUnitLauncherCore}, select the class, execute, read the
 * outcome off the session store). It names the engine ({@link JupiterTestEngine}) HERE — the engine
 * module owns the launcher machinery now, so a domain no longer carries the {@code
 * junit-jupiter-engine} dep just to feed its front-door; naming the class here is what makes bnd
 * wire the engine bundle into whoever plays.
 *
 * <p>It returns the LIVE {@link ScenarioOutcome} (the jGiven {@code ReportModel} as an object), NOT
 * a serialised envelope: the play, the harvest, and the caller all sit on the same in-container
 * worker (the in-realm hop {@link ScenarioOutcome} documents). The production caller — {@link
 * GenericRunbookHandler} — serialises it to a {@link RunbookEnvelope} for the realm crossing; an
 * in-container test caller keeps it live and asserts on the model directly, no JSON round-trip. The
 * loader is the scenario class's own (a bundle loader in-container), so the launch runs in the
 * domain's realm and the scenario resolves its {@code @OsgiService} collaborators from that
 * bundle's registry.
 */
public final class ScenarioPlayer {

  /**
   * What the player knows how to play — OUR marker every seed scenario implements, so the bound
   * stays in our own vocabulary (a scenario is {@code extends ScenarioTestBase<Given, When, Then>}
   * + {@code @SeedScenario}, neither a type we own; bounding on jGiven's {@code ScenarioTestBase}
   * would leak a third-party type into this API and pull its package onto the engine's imports).
   * Defined HERE, on the player, because it names the play relation directly: {@code Class<?
   * extends Playable>} reads as "a class the player can play". A pure marker for now — it can grow
   * default methods if a common play concern emerges, without touching the scenarios that already
   * implement it. {@code @SeedScenario} stays the annotation tag (the extension socle); this is its
   * compile-time type companion.
   */
  public interface Playable {}

  private final ScenarioOutcomeSeed outcomeSeed = new ScenarioOutcomeSeed();

  /**
   * Play {@code scenarioClass} in-container, seeding the launcher session store with {@code
   * seedSessionStore} before discovery (the activation input, the transaction id, the inherited
   * cellar entries — whatever the caller composed), and return the harvested {@link
   * ScenarioOutcome}.
   */
  public ScenarioOutcome play(
      Class<? extends Playable> scenarioClass,
      Consumer<NamespacedHierarchicalStore<Namespace>> seedSessionStore)
      throws InterruptedException {
    return new JUnitLauncherCore<ScenarioOutcome>()
        .run(
            scenarioClass.getClassLoader(),
            JupiterTestEngine.class,
            wiring -> List.of(DiscoverySelectors.selectClass(scenarioClass)),
            (launcher, request, sessionStore) -> {
              launcher.execute(request);
              return outcomeSeed.read(sessionStore);
            },
            seedSessionStore);
  }
}
