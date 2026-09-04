package io.seedmatic.rke2lab.ghapp.contract;

/**
 * The desired state of the one org-owned GitHub App's WEBHOOK configuration — the pair {@code PATCH
 * /app/hook/config} reconciles: the {@code url} the App POSTs its events to (the Tailscale funnel
 * FQDN, which changes on a funnel rename) and the {@code secret} the HMAC signature is computed
 * with (the single shared {@code github.webhook.secret} Pipelines-as-Code validates against).
 * Content type and {@code insecure_ssl} are edge constants (JSON, verified TLS), not state.
 *
 * <p>NOT the App's event SUBSCRIPTIONS nor its permissions: those are set once at registration (the
 * pre-filled form's {@code events[]} + permission params) and are not reachable through the hook
 * config endpoint. This record is only the two fields that legitimately drift after creation.
 */
public record WebhookConfig(String url, String secret) {}
