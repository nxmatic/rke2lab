package io.nxmatic.rke2lab.worktree.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.Quoted;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.CellarReceiver;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.OsgiService;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.SeedScenario;
import io.nxmatic.rke2lab.seed.broker.port.Cellar;
import io.nxmatic.rke2lab.seed.broker.port.Parcel;
import io.nxmatic.rke2lab.worktree.Worktree;
import io.nxmatic.rke2lab.worktree.host.WorktreeCoordinate;
import io.nxmatic.rke2lab.worktree.host.WorktreeFacts;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The worktree soil — a production jGiven scenario told in the WORKTREE DOMAIN's own vocabulary,
 * played IN-CONTAINER by the seeding engine like every other crossing. Its whole job is to HARVEST
 * the worktree's git facts into the cellar: it reads the {@link Worktree} {@code @Component} (which
 * self-locates its root and knows its HEAD provenance + working state) and stores a {@link
 * WorktreeFacts} at the {@link WorktreeCoordinate#FACTS} coordinate. The flat host then FETCHES
 * that harvest from the cellar — the root for the GROW mounts, the working state for the entry gate
 * — the same fetch-not-push discipline the incus {@code InstanceGrowPlan} rides (a domain-owned
 * coordinate keyed by its wire slug, so it crosses the realm through the cellar without the broker
 * ever routing a domain enum).
 *
 * <p>Its collaborator, the {@link Worktree} service, is INJECTED from the framework registry by the
 * {@link OsgiService} bridge — {@code worktree-core}'s {@code JgitWorktree} live, or a mock a test
 * seeds. NOT survey-inert: reading git facts is a pure, deterministic read (no live system
 * contacted), so the harvest runs in BOTH modes — the host needs the root to project the GROW
 * mounts at preview too, exactly as the incus grow plan is projected in both.
 */
@SeedScenario
public class WorktreeScenario
    extends ScenarioTestBase<WorktreeScenario.Given, WorktreeScenario.When, WorktreeScenario.Then>
    implements CellarReceiver<Cellar>, ScenarioPlayer.Playable {

  private final Scenario<Given, When, Then> scenario = createScenario();

  // The transactional cellar the extension injects before the body — the harvest is stored into it,
  // and the host reads it back within the run (read-your-writes). Null until receiveCellar sets it.
  private Cellar cellar;

  // The current parcel the host publishes at the GIVEN; the soil files its harvest under it.
  @OsgiService private Optional<Parcel> parcel = Optional.empty();

  @Override
  public Scenario<Given, When, Then> getScenario() {
    return scenario;
  }

  @Override
  public void receiveCellar(Cellar cellar) {
    this.cellar = cellar;
  }

  @Test
  void the_worktree_facts_are_harvested() {
    given().the_worktree("seed");
    when().the_git_facts_are_read();
    then().the_facts_are_harvested(cellar, parcel.orElseThrow());
  }

  /** Given: the worktree the seed cultivates — a name, for the readable line. */
  public static class Given extends Stage<Given> {

    public Given the_worktree(@Quoted String name) {
      return self();
    }
  }

  /**
   * When: read the worktree's git facts off the {@link Worktree} service and fabricate the {@link
   * WorktreeFacts} snapshot (WHEN fabricates). The service is injected straight from the bundle
   * registry by the {@link OsgiService} bridge — {@code JgitWorktree} live, jgit sealed behind it.
   */
  public static class When extends Stage<When> {

    @OsgiService private Optional<Worktree> worktree = Optional.empty();

    @ProvidedScenarioState WorktreeFacts facts;

    public When the_git_facts_are_read() {
      final Worktree resolved = worktree.orElseThrow();
      this.facts =
          new WorktreeFacts(
              resolved.root().toString(), resolved.provenance(), resolved.workingState());
      return self();
    }
  }

  /**
   * Then: file the harvest at {@link WorktreeCoordinate#FACTS} under the current parcel (THEN
   * seals). The store is unconditional on the gate — the cellar routes conserve vs pre-reserve
   * itself — so a preview run still records the facts the host reads back for the grow projection.
   */
  public static class Then extends Stage<Then> {

    @ExpectedScenarioState WorktreeFacts facts;

    public Then the_facts_are_harvested(@Hidden Cellar cellar, @Hidden Parcel parcel) {
      cellar.store(parcel, WorktreeCoordinate.FACTS, facts);
      return self();
    }
  }
}
