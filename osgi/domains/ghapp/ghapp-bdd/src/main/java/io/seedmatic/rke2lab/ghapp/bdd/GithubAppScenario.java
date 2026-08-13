package io.seedmatic.rke2lab.ghapp.bdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import io.seedmatic.rke2lab.ghapp.contract.GhAppCoordinate;
import io.seedmatic.rke2lab.ghapp.contract.GithubAppCredentials;
import io.seedmatic.rke2lab.ghapp.contract.GithubAppRegistrar;
import io.seedmatic.rke2lab.operator.OperatorNotifier;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.CellarReceiver;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.OsgiService;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioCellar;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.SeedScenario;
import io.seedmatic.rke2lab.seed.broker.port.Cellar;
import io.seedmatic.rke2lab.seed.broker.port.Parcel;
import io.seedmatic.rke2lab.seed.broker.port.SecretsGateway;
import io.seedmatic.rke2lab.seed.broker.port.Sensitivity;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.jupiter.api.Test;

/**
 * The ghapp registration scion — the in-container {@code @SeedScenario} the grow sows to ensure the
 * one org-owned GitHub App exists and its {@link GithubAppCredentials} are sealed. The identity
 * proof is the PRIVATE KEY, so the durable anchor is {@code .secrets} (sops-governed,
 * operator-owned, portable) — the twin of how the age key rests — and the cellar seal is the in-run
 * working copy.
 *
 * <p>The scion orchestrates a THREE-way branch, but never touches {@code .secrets} itself: the host
 * owns that I/O and publishes it as the {@link SecretsGateway} seam (like {@link
 * io.seedmatic.rke2lab.seed.broker.port.RunGate}), which the scion resolves and calls back into.
 *
 * <ol>
 *   <li>credentials already in the cellar → no-op (this run sealed them, idempotent-keep);
 *   <li>else in {@code .secrets} (via the gateway) → rehydrate: seal to the cellar, no
 *       registration;
 *   <li>else → register (live, the manifest flow) → seal to the cellar AND persist to {@code
 *       .secrets} through the gateway, so a fresh env skips the human step.
 * </ol>
 *
 * <p>GIVEN the operator's GitHub session; WHEN the App is registered (the fabrication — {@link
 * Optional#empty()} when already sealed, or when a survey/preview frontier filters the {@code
 * cultivating}-tagged edges out); THEN the credentials are filed SEALED, and persisted to {@code
 * .secrets} only if freshly registered. The G/W/T rule holds: the WHEN fabricates, the THEN seals.
 *
 * <p>It drives edges that are {@code @OsgiService(await = false)} (genuinely optional — absent
 * under a survey): {@link GithubAppRegistrar} runs the manifest flow, {@link OperatorNotifier} pops
 * the operator's browser toward the loopback endpoint, and {@link SecretsGateway} is the host's
 * {@code .secrets} door. The App manifest is a bundle resource, POSTed to GitHub by the operator's
 * browser.
 */
@SeedScenario
public class GithubAppScenario
    extends ScenarioTestBase<
        GithubAppScenario.Given, GithubAppScenario.When, GithubAppScenario.Then>
    implements CellarReceiver<ScenarioCellar>, ScenarioPlayer.Playable {

  private static final String SECRETS_KEY = "githubApp";

  private final Scenario<Given, When, Then> scenario = createScenario();

  /**
   * Injected by {@code ScenarioCellarExtension} before the body (store→tag, durable fallthrough).
   */
  @MonotonicNonNull private ScenarioCellar cellar;

  /** The current plot this run cultivates — injected from the bundle registry before the body. */
  @OsgiService private Optional<Parcel> parcel = Optional.empty();

  /** The registration edge — optional (the manifest flow); absent under a survey → PENDS. */
  @OsgiService(await = false)
  private Optional<GithubAppRegistrar> registrar = Optional.empty();

  /** The desktop-notification edge — optional; pops the operator's browser at the endpoint URL. */
  @OsgiService(await = false)
  private Optional<OperatorNotifier> notifier = Optional.empty();

  /**
   * The host's {@code .secrets} door — optional; absent under a survey means no rehydrate/persist.
   */
  @OsgiService(await = false)
  private Optional<SecretsGateway> secrets = Optional.empty();

  @Override
  public Scenario<Given, When, Then> getScenario() {
    return scenario;
  }

  @Override
  public void receiveCellar(ScenarioCellar cellar) {
    this.cellar = cellar;
  }

  @Test
  void the_github_app_is_registered_once() {
    final Parcel plot =
        parcel.orElseThrow(() -> new IllegalStateException("no Parcel injected before the body"));
    final ScenarioCellar tx =
        Objects.requireNonNull(
            cellar, "the ScenarioCellar was not injected before the scenario ran");
    given().the_operators_github_session();
    when().the_github_app_is_registered(plot, tx, registrar, notifier, secrets, manifestJson());
    then().the_github_app_credentials_are_filed(plot, tx, secrets);
  }

  private static String manifestJson() {
    try (InputStream in =
        GithubAppScenario.class.getResourceAsStream("seedmatic-automation.manifest.json")) {
      if (in == null) {
        throw new IllegalStateException("the App manifest resource is missing from the bundle");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("could not read the App manifest resource", e);
    }
  }

  /** GIVEN — the operator's GitHub session (narration; the manifest flow rides the browser). */
  public static class Given extends Stage<Given> {

    public Given the_operators_github_session() {
      return self();
    }
  }

  /**
   * WHEN — the App is registered (idempotent on a cellar hit; rehydrated from .secrets; else live).
   */
  public static class When extends Stage<When> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Credentials to seal to the cellar (from a cellar miss: rehydrate or register). */
    @ProvidedScenarioState Optional<GithubAppCredentials> credentials = Optional.empty();

    /** Credentials to persist to {@code .secrets} — only when freshly registered. */
    @ProvidedScenarioState Optional<GithubAppCredentials> persist = Optional.empty();

    @As("the github app is registered")
    public When the_github_app_is_registered(
        @Hidden Parcel parcel,
        @Hidden Cellar cellar,
        @Hidden Optional<GithubAppRegistrar> registrar,
        @Hidden Optional<OperatorNotifier> notifier,
        @Hidden Optional<SecretsGateway> secrets,
        @Hidden String manifestJson) {
      if (cellar
          .fetch(parcel, GhAppCoordinate.GITHUB_APP, GithubAppCredentials.class)
          .isPresent()) {
        return self();
      }
      final Optional<GithubAppCredentials> anchored =
          secrets.flatMap(gateway -> gateway.read(SECRETS_KEY)).map(When::fromJson);
      if (anchored.isPresent()) {
        this.credentials = anchored;
        return self();
      }
      if (registrar.isEmpty()) {
        return self();
      }
      final Consumer<URI> onEndpointReady =
          notifier.<Consumer<URI>>map(operator -> operator::browse).orElse(url -> {});
      try {
        final GithubAppCredentials fresh = registrar.get().register(manifestJson, onEndpointReady);
        this.credentials = Optional.of(fresh);
        this.persist = Optional.of(fresh);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("GitHub App registration interrupted", e);
      }
      return self();
    }

    private static GithubAppCredentials fromJson(String json) {
      try {
        final JsonNode node = MAPPER.readTree(json);
        return new GithubAppCredentials(
            node.path("appId").asText(),
            node.path("installationId").asText(),
            node.path("privateKeyPem").asText());
      } catch (IOException e) {
        throw new UncheckedIOException("could not parse the githubApp secrets block", e);
      }
    }
  }

  /**
   * THEN — the credentials are filed SEALED, and persisted to {@code .secrets} if freshly
   * registered.
   */
  public static class Then extends Stage<Then> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ExpectedScenarioState Optional<GithubAppCredentials> credentials;
    @ExpectedScenarioState Optional<GithubAppCredentials> persist;

    @As("the github app credentials are filed")
    public Then the_github_app_credentials_are_filed(
        @Hidden Parcel parcel, @Hidden Cellar cellar, @Hidden Optional<SecretsGateway> secrets) {
      credentials.ifPresent(
          value -> cellar.store(parcel, GhAppCoordinate.GITHUB_APP, value, Sensitivity.SEALED));
      persist.ifPresent(
          value ->
              secrets.ifPresent(
                  gateway -> gateway.write(SECRETS_KEY, toJson(value), Set.of("privateKeyPem"))));
      return self();
    }

    private static String toJson(GithubAppCredentials credentials) {
      final ObjectNode node = MAPPER.createObjectNode();
      node.put("appId", credentials.appId());
      node.put("installationId", credentials.installationId());
      node.put("privateKeyPem", credentials.privateKeyPem());
      return node.toString();
    }
  }
}
