package io.seedmatic.rke2lab.manifests.ingress;

/**
 * The Pipelines-as-Code webhook FUNNEL identity — the single source of truth for the Tailscale
 * MagicDNS leaf the PaC controller is funnel-exposed under, shared by the two parties that must
 * agree on it: the manifests {@code PacWebhookManifestsUnit} (which names the Ingress + its {@code
 * tls.hosts} leaf, letting the Tailscale operator provision {@code https://<leaf>.<tailnet>}) and
 * the host ghapp webhook scion (which points the GitHub App's webhook at that same {@code url}). A
 * rename of the funnel — the way we win a fresh Let's Encrypt cert budget — is thus ONE edit here,
 * never a leaf literal duplicated across modules that could drift.
 *
 * <p>It lives in the {@code manifests.ingress} DUAL-REALM face precisely because both realms
 * consume it: OSGi-side the manifest units read {@link #LEAF}, host-side the {@code seed-master}
 * grow builds {@link #url()} to sow the webhook scion and the {@code ghapp} CLI pre-fills the
 * registration form. A {@code manifests.contract} home would be bundle-only — a flat-realm
 * reference from the host would break the realm-boundary law. It qualifies for the dual-realm rule:
 * a pure String record, JDK-only, manifests-owned, no service reference.
 *
 * <p>A record over the {@code tailnet} (the host-config suffix, e.g. {@code mammoth-skate.ts.net} —
 * it ALREADY carries {@code .ts.net}, so {@link #url()} does not re-append it): {@link #url()} is
 * the funnel endpoint {@code https://<leaf>.<tailnet>}. Only the host holds the tailnet (Tailscale
 * appends it at runtime; it is never on the in-container synthesis context), so the manifests side
 * uses only {@link #LEAF} and the host builds the full {@code url()}.
 */
public record PacWebhookFunnel(String tailnet) {

  /** The MagicDNS leaf the PaC controller is funnel-exposed under — the funnel identity. */
  public static final String LEAF = "pipelines-webhook";

  /** The public funnel endpoint the GitHub App posts its webhook events to. */
  public String url() {
    return "https://" + LEAF + "." + tailnet;
  }
}
