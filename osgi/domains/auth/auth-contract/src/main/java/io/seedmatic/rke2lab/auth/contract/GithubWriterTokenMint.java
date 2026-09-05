package io.seedmatic.rke2lab.auth.contract;

import java.util.Optional;

/**
 * The auth domain's on-demand GitHub push-token verb: from the durable one-org-owned App
 * credentials (revealed by a consumer from its OWN cellar case), mint a FRESH {@code
 * contents:write} installation token AT THE POINT OF USE.
 *
 * <p>The App is the single source of trust; the token itself is ephemeral (≈1 h) and MUST NOT be
 * stored. Minting it seconds before the push and discarding it is what keeps it from going stale
 * between a mint and a much later reveal — the trap a durable seal fell into (an installation token
 * filed in the cellar before provisioning, revealed for the push after the whole cluster came up,
 * long past its 1 h life).
 *
 * <p>Pure-JDK signature by design: the credential fields cross as plain strings so no {@code
 * ghapp-contract} type is dragged into a consumer that deliberately mirrors the App material (the
 * manifests synthesis). The realised {@code auth-edge} impl delegates to the ghapp minter.
 *
 * <p>{@link Optional#empty()} when the token cannot be minted (the {@code cultivating}-gated edge
 * is filtered out of a survey/preview, or its mint dependency is absent) — the caller then skips
 * the push, honest inertness rather than a fabricated credential.
 */
public interface GithubWriterTokenMint {

  /** A fresh {@code contents:write} installation token for the one org-owned App, or empty. */
  Optional<String> mint(String appId, String installationId, String privateKeyPem);
}
