package io.seedmatic.rke2lab.ghapp.contract;

import io.seedmatic.rke2lab.seed.broker.port.SeedCoordinate;

/**
 * The ghapp domain's cellar coordinate — where the registration files the resolved {@link
 * GithubAppCredentials} and a consumer (the writer mint, the reader Secret render) fetches it back.
 * OSGi-only: the credentials are sealed AND revealed in-container. The slug matches the {@link
 * io.seedmatic.rke2lab.seed.broker.port.SeedContract} of the record filed under it, which {@code
 * SeedCodec} verifies at decode.
 */
public enum GhAppCoordinate implements SeedCoordinate {

  /** The one org-owned GitHub App's credentials (id + installation id + private key), SEALED. */
  GITHUB_APP("github-app");

  private static final String DOMAIN = "ghapp";

  private final String slug;

  GhAppCoordinate(String slug) {
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
