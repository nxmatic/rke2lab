// @codebase
package io.seedmatic.rke2lab.manifests.contract.profiles;

import java.util.Objects;

/**
 * The one org-owned GitHub App's identity + private key published to synth-time layers via {@code
 * ManifestSynthesisContext}. Backs the {@code githubapp} Secret (see {@code
 * GithubAppSecretManifestsUnit}) that Flux consumes for its native App auth — the App id, the
 * installation id, and the App's one private key, from which Flux self-mints {@code contents:read}
 * pull tokens.
 *
 * <p>The manifests-side MIRROR of the {@code ghapp} {@code GithubAppCredentials} sealed record, the
 * exact twin of {@link OperatorPkiMaterial}'s treatment of cluster-pki: the manifests scion reveals
 * the sealed credentials from the cellar in-container (by the neutral {@code github-app} wire slug)
 * and translates them here before handing them to synthesis, so no {@code ghapp} type ever crosses
 * into the manifests domain — the gate boundary stays clean, and the standalone {@code
 * manifests-cli} assembly never drags {@code ghapp-contract}'s flat copy.
 *
 * <p>Absence — no App credentials sealed yet (unit tests, a bare survey, before the registration
 * filed) — is carried as an empty {@code Optional<GithubAppMaterial>} on the context, never a
 * placeholder: a present material always holds a real key, so the unit renders the Secret
 * unconditionally.
 */
public record GithubAppMaterial(String appId, String installationId, String privateKeyPem) {

  public GithubAppMaterial {
    appId = Objects.requireNonNull(appId, "appId");
    installationId = Objects.requireNonNull(installationId, "installationId");
    privateKeyPem = Objects.requireNonNull(privateKeyPem, "privateKeyPem");
  }
}
