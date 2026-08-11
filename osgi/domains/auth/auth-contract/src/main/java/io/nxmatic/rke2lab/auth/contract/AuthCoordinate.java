package io.nxmatic.rke2lab.auth.contract;

import io.nxmatic.rke2lab.seed.broker.port.SeedCoordinate;

/**
 * The auth domain's cellar coordinate — where the auth-seal step files a resolved credential and a
 * consumer fetches it back. OSGi-only (a {@code type=contract} enum): the token is sealed AND
 * revealed in-container, so unlike the dual-realm {@code ClusterPkiCoordinate} it never needs a
 * host copy. The {@code Cellar} keys on the slug/domain STRINGS, so the seal scion stores under
 * this coordinate and the rendered-branch scion fetches under its own copy.
 *
 * <p>The slug matches the {@link SeedContract} of the record filed under it ({@link GithubToken}),
 * which {@code SeedCodec} verifies at decode.
 */
public enum AuthCoordinate implements SeedCoordinate {

  /**
   * The GitHub token, filed SEALED by the auth-seal, revealed by the rendered-branch force-push.
   */
  GITHUB_TOKEN("github-token");

  private static final String DOMAIN = "auth";

  private final String slug;

  AuthCoordinate(String slug) {
    this.slug = slug;
  }

  @Override
  public String slug() {
    return slug;
  }

  @Override
  public String domain() {
    return DOMAIN;
  }
}
