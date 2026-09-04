package io.seedmatic.rke2lab.ghapp.contract;

/**
 * The ghapp domain's webhook-reconcile verb: from the sealed {@link GithubAppCredentials},
 * authenticate AS the App (a JWT signed with its private key) and set its webhook {@link
 * WebhookConfig} via {@code PATCH /app/hook/config}. The reconcile twin of {@link GithubAppMinter}:
 * mint obtains a per-scope installation token, this re-points the App's own webhook — the App-level
 * setting the operator would otherwise change by hand in the GitHub UI on every funnel rename.
 *
 * <p>Idempotent and fail-fast: a successful PATCH leaves the App pointing at {@code config.url()}
 * with {@code config.secret()}; a non-2xx response throws — never a silent no-op. It does NOT
 * create the App nor touch its event subscriptions/permissions (set once at registration): it only
 * reconciles the two hook-config fields that drift.
 */
public interface GithubAppWebhookConfigurer {

  /**
   * Set the App's webhook to {@code config}, authenticating AS the App with {@code credentials}.
   */
  void configure(GithubAppCredentials credentials, WebhookConfig config);
}
