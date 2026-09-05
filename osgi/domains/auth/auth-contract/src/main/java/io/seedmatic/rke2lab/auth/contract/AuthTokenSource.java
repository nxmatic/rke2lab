package io.seedmatic.rke2lab.auth.contract;

/**
 * The credential providers an {@link AuthTokenContact} can be asked to resolve a short-lived token
 * for. A seam enum — the flat name of the provider crosses; the host maps its own env-var
 * precedence at the call site and only asks the contact when the environment yielded nothing.
 *
 * <p>GitHub is NOT a source here: GitHub credentials flow from the one org-owned App via {@code
 * ghapp} — a consumer mints a fresh scoped token ON DEMAND through {@link GithubWriterTokenMint},
 * never a personal {@code gh auth token}. FloxHub remains a CLI-sourced token, so this enum stays.
 */
public enum AuthTokenSource {

  /** FloxHub, resolved by the edge via {@code flox auth token}. */
  FLOXHUB
}
