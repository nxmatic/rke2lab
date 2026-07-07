package io.nxmatic.rke2lab.auth.port;

/**
 * The credential providers an {@link AuthTokenContact} can be asked to resolve a short-lived token
 * for. A seam enum — the flat name of the provider crosses; the host maps its own env-var
 * precedence at the call site and only asks the contact when the environment yielded nothing.
 */
public enum AuthTokenSource {

  /** GitHub, resolved by the edge via {@code gh auth token}. */
  GITHUB,

  /** FloxHub, resolved by the edge via {@code flox auth token}. */
  FLOXHUB
}
