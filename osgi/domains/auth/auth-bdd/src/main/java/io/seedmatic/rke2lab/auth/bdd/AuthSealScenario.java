package io.seedmatic.rke2lab.auth.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import io.seedmatic.rke2lab.auth.contract.AuthCoordinate;
import io.seedmatic.rke2lab.auth.contract.GithubToken;
import io.seedmatic.rke2lab.ghapp.contract.GhAppCoordinate;
import io.seedmatic.rke2lab.ghapp.contract.GithubAppCredentials;
import io.seedmatic.rke2lab.ghapp.contract.GithubAppMinter;
import io.seedmatic.rke2lab.ghapp.contract.MintedToken;
import io.seedmatic.rke2lab.ghapp.contract.TokenScope;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.CellarReceiver;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.OsgiService;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioCellar;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.SeedScenario;
import io.seedmatic.rke2lab.seed.broker.port.Cellar;
import io.seedmatic.rke2lab.seed.broker.port.Parcel;
import io.seedmatic.rke2lab.seed.broker.port.Sensitivity;
import java.util.Objects;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.jupiter.api.Test;

/**
 * The auth-seal scion — the in-container {@code @SeedScenario} that files the short-lived GitHub
 * push token SEALED, the ONE place the {@code auth} domain DELEGATES its GitHub sourcing to {@code
 * ghapp}. It no longer shells {@code gh auth token} (retired): it reveals the sealed {@link
 * GithubAppCredentials} the ghapp registration filed at {@link GhAppCoordinate#GITHUB_APP} and
 * mints a {@code WRITER}-scoped installation token from the one org-owned App, filing it as a
 * {@link GithubToken} at {@link AuthCoordinate#GITHUB_TOKEN}.
 *
 * <p>Why the delegation lives HERE (a scenario), not in an edge: revealing the credentials needs
 * the run's {@code Cellar} + {@code Parcel}, which a stateless {@code @Component} edge does not
 * hold. So auth-seal is the single delegation point; every GitHub consumer (the rendered-branch
 * push, the release query) reveals {@code GithubToken} uniformly, knowing nothing of ghapp, the
 * App, or the mint — one source of trust, one abstraction.
 *
 * <p>GIVEN the operator's GitHub session; WHEN the token is minted (the fabrication — {@link
 * Optional#empty()} when the {@code cultivating}-tagged {@link GithubAppMinter} edge is filtered
 * out of a survey frontier, or the credentials are not yet sealed); THEN it is filed, only if
 * present. The seal is NOT idempotent-keep: an installation token is ephemeral (≈1 h), so each run
 * re-mints and re-seals the current one. {@code WRITER} also reads, so it serves both the push and
 * the query.
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

  /** The App-token minter — optional (the ghapp edge); absent (survey) seals nothing. */
  @OsgiService(await = false)
  private Optional<GithubAppMinter> minter = Optional.empty();

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
    when().the_github_token_is_minted(plot, tx, minter);
    then().the_github_token_is_filed(plot, tx);
  }

  /** GIVEN — the operator's GitHub session (narration; the App identity is sealed by ghapp). */
  public static class Given extends Stage<Given> {

    public Given the_operators_github_session() {
      return self();
    }
  }

  /** WHEN — a WRITER token is minted from the sealed App credentials (empty when absent). */
  public static class When extends Stage<When> {

    @ProvidedScenarioState Optional<GithubToken> token = Optional.empty();

    @As("the github token is minted")
    public When the_github_token_is_minted(
        @Hidden Parcel parcel, @Hidden Cellar cellar, @Hidden Optional<GithubAppMinter> minter) {
      this.token =
          minter
              .flatMap(
                  mint ->
                      cellar
                          .fetch(parcel, GhAppCoordinate.GITHUB_APP, GithubAppCredentials.class)
                          .map(credentials -> mint.mint(credentials, TokenScope.WRITER)))
              .map(MintedToken::token)
              .map(GithubToken::new);
      return self();
    }
  }

  /** THEN — the token is filed SEALED, only if it was minted. */
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
