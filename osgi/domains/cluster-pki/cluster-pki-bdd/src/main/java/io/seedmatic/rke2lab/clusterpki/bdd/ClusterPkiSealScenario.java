package io.seedmatic.rke2lab.clusterpki.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.ScenarioState.Resolution;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import io.seedmatic.rke2lab.clusterpki.contract.ClusterCaBundle;
import io.seedmatic.rke2lab.clusterpki.contract.ClusterPkiCoordinate;
import io.seedmatic.rke2lab.clusterpki.contract.SopsDecryptor;
import io.seedmatic.rke2lab.clusterpki.contract.SopsEncryptor;
import io.seedmatic.rke2lab.clusterpki.contract.WebhookServingCredentials;
import io.seedmatic.rke2lab.clusterpki.core.ClusterSeal;
import io.seedmatic.rke2lab.clusterpki.core.SealedClusterPki;
import io.seedmatic.rke2lab.manifests.contract.SshToAgeConverter;
import io.seedmatic.rke2lab.ndh.contract.NdhKeystoreReader;
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
 * The cluster-PKI seal scion — the in-container {@code @SeedScenario} the host sows ONCE per
 * cluster (the crossing before provisioning), in the cluster-pki domain's own register. It mints
 * the deterministic CA rooted on the operator's ndh {@code mammoth-skate-tls} root and files it for
 * the host GROW to pose over devlxd.
 *
 * <p>GIVEN the operator's root of trust; WHEN the CA is sealed (the fabrication — idempotent: a
 * prior grow's bundle in the cellar means the CA already exists, so it is KEPT, never re-minted,
 * and the CA stays stable across the destroy/recreate loop); THEN the PKI is filed — {@link
 * ClusterCaBundle} {@code PLAIN} (it is already sops-sealed) and {@link
 * io.seedmatic.rke2lab.clusterpki.contract.ClusterAgeKey} {@code SEALED} (the {@code CellarCipher},
 * so the decryption key never rests in the clear). The G/W/T rule holds: the WHEN fabricates, the
 * THEN seals to the cellar.
 *
 * <p>It takes NO host input — {@link ClusterSeal} reads {@code keys.yaml} / {@code .sops.yaml}
 * in-container. The two external seams are {@code @OsgiService}-injected from THIS bundle's
 * registry ({@link SshToAgeConverter}, realised by ssh-to-age-edge; {@link SopsEncryptor}, by
 * sops-edge) and handed to {@code ClusterSeal} (instance-passing). The seal is a pure computation
 * (crypto + local tool reads), so it runs in both preview and live; the cellar routes the store
 * (pre-reserve vs conserve). See docs/architecture/cluster-api/deterministic-cluster-access.adoc.
 */
@SeedScenario
public class ClusterPkiSealScenario
    extends ScenarioTestBase<
        ClusterPkiSealScenario.Given, ClusterPkiSealScenario.When, ClusterPkiSealScenario.Then>
    implements CellarReceiver<ScenarioCellar>, ScenarioPlayer.Playable {

  private final Scenario<Given, When, Then> scenario = createScenario();

  /**
   * Injected by {@code ScenarioCellarExtension} before the body (store→tag, durable fallthrough).
   */
  @MonotonicNonNull private ScenarioCellar cellar;

  /** The current plot this run cultivates — injected from the bundle registry before the body. */
  @OsgiService private Optional<Parcel> parcel = Optional.empty();

  /** The collaborators the seal drives — injected from THIS bundle's registry. */
  @OsgiService private Optional<NdhKeystoreReader> keystore = Optional.empty();

  @OsgiService private Optional<SshToAgeConverter> sshToAge = Optional.empty();

  @OsgiService private Optional<SopsEncryptor> encryptor = Optional.empty();

  @OsgiService private Optional<SopsDecryptor> decryptor = Optional.empty();

  @Override
  public Scenario<Given, When, Then> getScenario() {
    return scenario;
  }

  @Override
  public void receiveCellar(ScenarioCellar cellar) {
    this.cellar = cellar;
  }

  @Test
  void the_cluster_ca_is_sealed_once() {
    final Parcel plot =
        parcel.orElseThrow(() -> new IllegalStateException("no Parcel injected before the body"));
    final ScenarioCellar tx =
        Objects.requireNonNull(
            cellar, "the ScenarioCellar was not injected before the scenario ran");
    given().the_operators_root_of_trust();
    when()
        .the_cluster_ca_is_sealed(
            plot,
            tx,
            keystore.orElseThrow(() -> new IllegalStateException("no NdhKeystoreReader")),
            sshToAge.orElseThrow(() -> new IllegalStateException("no SshToAgeConverter edge")),
            encryptor.orElseThrow(() -> new IllegalStateException("no SopsEncryptor edge")),
            decryptor.orElseThrow(() -> new IllegalStateException("no SopsDecryptor edge")));
    then().the_cluster_pki_is_filed(plot, tx);
  }

  /**
   * GIVEN — the operator's root of trust is present (narration; the seal reads it in-container).
   */
  public static class Given extends Stage<Given> {

    public Given the_operators_root_of_trust() {
      return self();
    }
  }

  /**
   * WHEN — the CA is sealed. Fresh cluster: mint everything. Existing cluster (cellar hit): the CA
   * is KEPT (never re-minted — stable across re-grows), and only the sealed cases ABSENT from the
   * cellar are minted additively, reusing the CA we already possess. Both fabrications are carried
   * to the THEN. Two erased {@code Optional}s share the stage, so name-resolve them.
   */
  public static class When extends Stage<When> {

    @ProvidedScenarioState(resolution = Resolution.NAME)
    Optional<SealedClusterPki> sealed = Optional.empty();

    @ProvidedScenarioState(resolution = Resolution.NAME)
    Optional<WebhookServingCredentials> additiveWebhookServing = Optional.empty();

    @As("the cluster CA is sealed")
    public When the_cluster_ca_is_sealed(
        @Hidden Parcel parcel,
        @Hidden Cellar cellar,
        @Hidden NdhKeystoreReader keystore,
        @Hidden SshToAgeConverter sshToAge,
        @Hidden SopsEncryptor encryptor,
        @Hidden SopsDecryptor decryptor) {
      final ClusterSeal seal = new ClusterSeal(keystore, sshToAge, encryptor, decryptor);
      final Optional<ClusterCaBundle> existing =
          cellar.fetch(parcel, ClusterPkiCoordinate.CLUSTER_CA_BUNDLE, ClusterCaBundle.class);
      if (existing.isEmpty()) {
        this.sealed = Optional.of(seal.seal());
      } else {
        // Additive: mint only the cases the cellar lacks (here the webhook serving cert, added
        // after this cluster was first sealed), from the CA recovered out of the existing bundle.
        final boolean webhookMissing =
            cellar
                .fetch(
                    parcel, ClusterPkiCoordinate.WEBHOOK_SERVING, WebhookServingCredentials.class)
                .isEmpty();
        if (webhookMissing) {
          this.additiveWebhookServing =
              Optional.of(seal.mintWebhookServing(existing.orElseThrow()));
        }
      }
      return self();
    }
  }

  /**
   * THEN — the PKI is filed: the bundle PLAIN (already sealed), the age identity SEALED, the
   * operator's admin credentials SEALED (they carry the admin private key).
   */
  public static class Then extends Stage<Then> {

    @ExpectedScenarioState(resolution = Resolution.NAME)
    Optional<SealedClusterPki> sealed;

    @ExpectedScenarioState(resolution = Resolution.NAME)
    Optional<WebhookServingCredentials> additiveWebhookServing;

    @As("the cluster PKI is filed")
    public Then the_cluster_pki_is_filed(@Hidden Parcel parcel, @Hidden Cellar cellar) {
      // Fresh seal: file the whole PKI.
      sealed.ifPresent(
          pki -> {
            cellar.store(parcel, ClusterPkiCoordinate.CLUSTER_CA_BUNDLE, pki.bundle());
            cellar.store(
                parcel, ClusterPkiCoordinate.CLUSTER_AGE_KEY, pki.ageKey(), Sensitivity.SEALED);
            cellar.store(
                parcel,
                ClusterPkiCoordinate.ADMIN_CREDENTIALS,
                pki.adminCredentials(),
                Sensitivity.SEALED);
            cellar.store(
                parcel,
                ClusterPkiCoordinate.WEBHOOK_SERVING,
                pki.webhookServing(),
                Sensitivity.SEALED);
          });
      // Additive re-seal (existing cluster): file only the newly-minted case.
      additiveWebhookServing.ifPresent(
          ws -> cellar.store(parcel, ClusterPkiCoordinate.WEBHOOK_SERVING, ws, Sensitivity.SEALED));
      return self();
    }
  }
}
