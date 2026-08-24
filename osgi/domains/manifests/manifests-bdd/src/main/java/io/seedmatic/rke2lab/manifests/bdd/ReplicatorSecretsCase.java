package io.seedmatic.rke2lab.manifests.bdd;

import io.seedmatic.rke2lab.seed.broker.port.SeedCoordinate;

/**
 * The cellar case the {@code replicator-secrets} seal files the SOURCE-secret material SEALED at,
 * and the manifests synthesis reveals it from. Both ends live in the manifests domain (the seal
 * {@link ReplicatorSecretsSealScenario} and the reveal in {@code ManifestSynthesisScenario}), so —
 * unlike the ghapp {@code GhAppCase} neutral-slug dance across two modules — one shared coordinate
 * addresses the store and the fetch. The cellar matches store/fetch by slug.
 */
public enum ReplicatorSecretsCase implements SeedCoordinate {
  REPLICATOR_SECRETS;

  @Override
  public String slug() {
    return "replicator-secrets";
  }

  @Override
  public String domain() {
    return "manifests";
  }
}
