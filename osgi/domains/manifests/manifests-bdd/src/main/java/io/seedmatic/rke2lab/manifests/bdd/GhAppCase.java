package io.seedmatic.rke2lab.manifests.bdd;

import io.seedmatic.rke2lab.seed.broker.port.SeedCoordinate;

/**
 * The ghapp registration's {@code github-app} cellar case, addressed by its NEUTRAL wire coordinate
 * so the manifests realm reveals the sealed App credentials without a compile link to {@code
 * ghapp-contract} (naming {@code GhAppCoordinate} would drag its flat copy into the standalone
 * {@code manifests-cli} assembly). The {@code slug}/{@code domain} here MUST match {@code
 * GhAppCoordinate.GITHUB_APP}; the cellar matches a read case by slug.
 *
 * <p>Shared by the two manifests consumers of the App material — the reader Secret render ({@code
 * ManifestSynthesisScenario#revealGithubApp}) and the on-demand push-token mint (both the synthesis
 * push and the version-bump release query) — so the neutral coordinate is declared once.
 */
public enum GhAppCase implements SeedCoordinate {
  GITHUB_APP;

  @Override
  public String slug() {
    return "github-app";
  }

  @Override
  public String domain() {
    return "ghapp";
  }
}
