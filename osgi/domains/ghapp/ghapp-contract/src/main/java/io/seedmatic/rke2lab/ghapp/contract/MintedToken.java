package io.seedmatic.rke2lab.ghapp.contract;

import java.time.Instant;

/**
 * A short-lived (≈1 h) GitHub App installation access token, minted for one {@link TokenScope}. It
 * is NOT sealed and NOT a cellar case: it is fabricated on demand from {@link
 * GithubAppCredentials}, used at once (the writer's push), and discarded — never filed. {@code
 * expiresAt} is the GitHub-set expiry, informational for a consumer deciding to re-mint.
 */
public record MintedToken(String token, Instant expiresAt) {}
