package io.nxmatic.rke2lab.auth.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import io.nxmatic.rke2lab.auth.contract.AuthCoordinate;
import io.nxmatic.rke2lab.auth.contract.AuthTokenContact;
import io.nxmatic.rke2lab.auth.contract.AuthTokenSource;
import io.nxmatic.rke2lab.auth.contract.GithubToken;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.CellarReceiver;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.OsgiService;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioCellar;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.SeedScenario;
import io.nxmatic.rke2lab.seed.broker.port.Cellar;
import io.nxmatic.rke2lab.seed.broker.port.Parcel;
import io.nxmatic.rke2lab.seed.broker.port.Sensitivity;
import java.util.Objects;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.jupiter.api.Test;

/**
 * The auth-seal scion — the in-container {@code @SeedScenario} the host sows early (before any
 * crossing that must authenticate to GitHub) to file a short-lived GitHub token in the cellar
 * SEALED, mirroring the cluster-PKI seal. It resolves the token from the ONE source of trust — the
 * {@link AuthTokenContact} edge ({@code gh auth token}), never an ambient environment variable —
 * and files it at {@link AuthCoordinate#GITHUB_TOKEN} SEALED, so the rendered-branch force-push
 * reveals it on fetch and never re-shells {@code gh} nor reads a stray env token.
 *
 * <p>GIVEN the operator's GitHub session; WHEN the token is resolved (the fabrication — {@link
 * Optional#empty()} when the edge is absent/unauthenticated, e.g. a survey run whose frontier
 * filters the {@code cultivating}-tagged contact out); THEN it is filed, only if present. The G/W/T
 * rule holds: the WHEN fabricates, the THEN seals to the cellar.
 *
 * <p>It takes NO host input. Its single collaborator, {@link AuthTokenContact}, is {@link
 * OsgiService}-injected ({@code await = false} — genuinely optional: a run with no edge seals no
 * token, and the push, opt-in and default-off, simply does not run). Unlike a re-mint, the seal is
 * NOT idempotent-keep: a token is ephemeral, so each run re-resolves and re-seals the current one.
 */
@SeedScenario
public class AuthSealScenario
    extends ScenarioTestBase<AuthSealScenario.Given, AuthSealScenario.When, AuthSealScenario.Then>
    implements CellarReceiver<ScenarioCellar>, ScenarioPlayer.Playable {

  private final Scenario<Given, When, Then> scenario = createScenario();

  /**
   * Injected by {@code ScenarioCellarExtension} before the body (store→tag, durable fallthrough).
   */
  @MonotonicNonNull private ScenarioCellar cellar;

  /** The current plot this run cultivates — injected from the bundle registry before the body. */
  @OsgiService private Optional<Parcel> parcel = Optional.empty();

  /** The GitHub token edge — optional ({@code gh auth token}); absent seals nothing. */
  @OsgiService(await = false)
  private Optional<AuthTokenContact> authToken = Optional.empty();

  @Override
  public Scenario<Given, When, Then> getScenario() {
    return scenario;
  }

  @Override
  public void receiveCellar(ScenarioCellar cellar) {
    this.cellar = cellar;
  }

  @Test
  void the_github_token_is_sealed() {
    final Parcel plot =
        parcel.orElseThrow(() -> new IllegalStateException("no Parcel injected before the body"));
    final ScenarioCellar tx =
        Objects.requireNonNull(
            cellar, "the ScenarioCellar was not injected before the scenario ran");
    given().the_operators_github_session();
    when().the_github_token_is_resolved(authToken);
    then().the_github_token_is_filed(plot, tx);
  }

  /** GIVEN — the operator's GitHub session (narration; the seal reads it via the edge). */
  public static class Given extends Stage<Given> {

    public Given the_operators_github_session() {
      return self();
    }
  }

  /** WHEN — the token is resolved from the edge (empty when absent), carried to the THEN. */
  public static class When extends Stage<When> {

    @ProvidedScenarioState Optional<GithubToken> token = Optional.empty();

    @As("the github token is resolved")
    public When the_github_token_is_resolved(@Hidden Optional<AuthTokenContact> authToken) {
      this.token =
          authToken
              .flatMap(contact -> contact.tokenFor(AuthTokenSource.GITHUB))
              .map(GithubToken::new);
      return self();
    }
  }

  /** THEN — the token is filed SEALED, only if the edge yielded one. */
  public static class Then extends Stage<Then> {

    @ExpectedScenarioState Optional<GithubToken> token;

    @As("the github token is filed")
    public Then the_github_token_is_filed(@Hidden Parcel parcel, @Hidden Cellar cellar) {
      token.ifPresent(
          value -> cellar.store(parcel, AuthCoordinate.GITHUB_TOKEN, value, Sensitivity.SEALED));
      return self();
    }
  }
}
