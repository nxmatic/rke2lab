package io.nxmatic.rke2lab.auth.contract;

import io.nxmatic.rke2lab.seed.broker.port.SeedContract;

/**
 * A short-lived GitHub token as a SEALED cellar case — the auth-seal step resolves it ONCE from the
 * one source of trust ({@code gh auth token}, via {@link AuthTokenContact}; never an ambient
 * environment variable) and files it SEALED ({@code Sensitivity.SEALED}, {@code CellarCipher} at
 * rest, so the credential never sits in the clear in the cellar). A consumer that must authenticate
 * to GitHub — the rendered-branch force-push — reveals it on fetch and holds it only for the single
 * push.
 *
 * <p>OSGi-only: the token is sealed AND revealed in-container (the seal scion stores it; the
 * rendered-branch scion, which {@code @Reference}s the OSGi {@code RenderedBranch}, reveals it), so
 * unlike the dual-realm cluster-PKI cases it never crosses the host membrane. {@link SeedContract}
 * binds it to the {@code github-token} coordinate for the codec's decode guard.
 */
@SeedContract("github-token")
public record GithubToken(String token) {}
