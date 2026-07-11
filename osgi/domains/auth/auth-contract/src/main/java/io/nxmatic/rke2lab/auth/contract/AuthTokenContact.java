package io.nxmatic.rke2lab.auth.contract;

import java.util.Optional;

/**
 * The auth domain's external-contact seam: resolve a short-lived credential for one {@link
 * AuthTokenSource} by asking its CLI. The {@code auth-edge} provides it by shelling {@code gh auth
 * token} / {@code flox auth token}; the host launch-secrets updater composes it after its own
 * environment-variable precedence has come up empty, then upserts the token into the launch-secrets
 * YAML.
 *
 * <p>The grain is fine and stateless — one call asks ONE provider for a token as it is NOW. The
 * contact owns no precedence and no persistence.
 */
public interface AuthTokenContact {

  /**
   * The token the given provider's CLI reports right now. Returns {@link Optional#empty()} when the
   * CLI is absent, unauthenticated, or exits non-zero — never throws for a missing token, so the
   * host reads the empty result and leaves the secrets file untouched. A present value is trimmed
   * and non-blank.
   */
  Optional<String> tokenFor(AuthTokenSource source);
}
