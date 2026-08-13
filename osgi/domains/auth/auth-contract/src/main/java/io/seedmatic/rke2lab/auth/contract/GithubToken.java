package io.seedmatic.rke2lab.auth.contract;

import io.seedmatic.rke2lab.seed.broker.port.SeedContract;

/**
 * A short-lived GitHub token as a SEALED cellar case — the auth-seal step mints it ONCE from the
 * one source of trust (the org-owned App via {@code ghapp}; never a personal {@code gh auth token}
 * nor an ambient environment variable) and files it SEALED ({@code Sensitivity.SEALED}, {@code
 * CellarCipher} at rest, so the credential never sits in the clear in the cellar). Every GitHub
 * consumer — the rendered-branch force-push, the version-bump release query — reveals it on fetch
 * uniformly, knowing nothing of ghapp or the App, and holds it only for its call.
 *
 * <p>OSGi-only: the token is sealed AND revealed in-container (the seal scion stores it; the
 * rendered-branch scion, which {@code @Reference}s the OSGi {@code RenderedBranch}, reveals it), so
 * unlike the dual-realm cluster-PKI cases it never crosses the host membrane. {@link SeedContract}
 * binds it to the {@code github-token} coordinate for the codec's decode guard.
 */
@SeedContract("github-token")
public record GithubToken(String token) {}
