package io.seedmatic.rke2lab.ghapp.contract;

import io.seedmatic.rke2lab.seed.broker.port.Amendment;
import io.seedmatic.rke2lab.seed.broker.port.SeedContract;

/**
 * The wire contract for the ghapp {@code webhook} runbook trigger — the activation payload a sower
 * supplies to play the webhook-reconcile scion. It carries a SINGLE {@link Amendment}: {@link
 * Amendment#FUNNEL} — {@link #funnelUrl} is the public funnel endpoint the App must POST to, which
 * only the host knows (the MagicDNS leaf is a manifest constant but the tailnet suffix is
 * host-config, and Tailscale appends it at runtime — never on the in-container synthesis context).
 * The host fills it by role — the FUNNEL amendment — never by field name.
 *
 * <p>The webhook SECRET is NOT carried here: the scion reads it in-container from {@code .secrets}
 * (the shared {@code github.webhook.secret}), the same door the ghapp scenario rehydrates the App
 * credentials through. Only the funnel URL is a host-held fact.
 *
 * <p>The {@code @SeedContract} slug is {@code "runbook"} — {@code RunbookCoordinate.SLUG} — so the
 * amend reflector's bearer index resolves this record for the {@code ghapp-webhook} soil.
 */
@SeedContract("runbook")
public record WebhookReconcileInput(@Amendment(Amendment.FUNNEL) String funnelUrl) {}
