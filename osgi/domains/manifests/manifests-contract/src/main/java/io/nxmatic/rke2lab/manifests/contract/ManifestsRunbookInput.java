package io.nxmatic.rke2lab.manifests.contract;

import io.nxmatic.rke2lab.seed.broker.port.SeedContract;

/**
 * The wire contract for the manifests {@code runbook} trigger — the activation payload a sower must
 * supply to play the manifests scion. It is the INPUT twin of a reaped wire-record: the {@code
 * shape} meta-coordinate projects THIS record's JSON Schema so a sower learns the shape from the
 * broker door rather than compiling the class (see docs/architecture/osgi/seed-broker-spec.adoc §
 * introspection).
 *
 * <p>Its top-level components ARE the {@code rke2lab:policy:} concern keys of {@code
 * Pulumi.dev.yaml} — {@code link} and {@code debug} — and each nested record mirrors that concern's
 * yaml sub-map EXACTLY, nesting and all. This is the design constraint the config path rests on
 * (see docs/architecture/osgi/manifests-bdd-spec.adoc § the single path): the host (seed AND CLI)
 * plucks {@code policy.link} / {@code policy.debug} from the yaml VERBATIM — a blind subtree copy
 * guided by the schema's top-level names — so the pluck stays generic, naming no manifests
 * vocabulary. The schema matching the yaml is what makes the copy blind.
 *
 * <p>Because the host only forwards subtrees, ALL the domain knowledge lives in the scion
 * (OSGi-side): it decodes this record (jackson coerces the yaml's string {@code "true"} to {@code
 * boolean}), flattens the nesting, and translates into its own vocabulary — {@link
 * ManifestDomainPolicy} (synth-time filter) + {@code FloxDebugPolicy} (per-layer debug) + the
 * {@code RKE2LAB_POLICY_LINK_*} link-time overlay, the projection {@code HostSlotManifest} used to
 * make host-side before the demolition orphaned it. The host names no {@code manifests.contract}
 * translation type.
 */
@SeedContract("runbook")
public record ManifestsRunbookInput(LinkFacet link, DebugFacet debug) {

  public ManifestsRunbookInput {
    link = link == null ? LinkFacet.defaults() : link;
    debug = debug == null ? DebugFacet.disabled() : debug;
  }

  /**
   * The {@code policy.link} concern: which manifest layers the master links live. Flat booleans,
   * mirroring the yaml sub-map exactly; the scion feeds them to {@link
   * ManifestDomainPolicy.Builder} and the {@code RKE2LAB_POLICY_LINK_*} overlay. An absent key
   * defaults to the operator's usual posture (everything on except {@code mesh}) so a partial yaml
   * still yields a complete facet.
   */
  public record LinkFacet(
      boolean gitops,
      boolean networking,
      boolean clusterApi,
      boolean storage,
      boolean mesh,
      boolean highAvailability,
      boolean cicd) {

    public static LinkFacet defaults() {
      return new LinkFacet(true, true, true, true, false, true, true);
    }
  }

  /**
   * The {@code policy.debug} concern, mirroring the yaml's {@code enabled}-wrapper nesting exactly
   * ({@code debug.mesh.enabled}, {@code debug.networking.enabled}, {@code
   * debug.nriPlugins.flox.enabled}). The scion flattens it into the three booleans {@code
   * FloxDebugPolicy} carries. An absent toggle defaults to off.
   */
  public record DebugFacet(Toggle mesh, Toggle networking, NriPlugins nriPlugins) {

    public DebugFacet {
      mesh = mesh == null ? Toggle.off() : mesh;
      networking = networking == null ? Toggle.off() : networking;
      nriPlugins = nriPlugins == null ? NriPlugins.off() : nriPlugins;
    }

    public static DebugFacet disabled() {
      return new DebugFacet(Toggle.off(), Toggle.off(), NriPlugins.off());
    }
  }

  /** The {@code {enabled: bool}} sub-object the yaml wraps each debug toggle in. */
  public record Toggle(boolean enabled) {
    private static final Toggle OFF = new Toggle(false);

    public static Toggle off() {
      return OFF;
    }
  }

  /** The {@code debug.nriPlugins} sub-map — a single {@code flox} toggle. */
  public record NriPlugins(Toggle flox) {

    public NriPlugins {
      flox = flox == null ? Toggle.off() : flox;
    }

    public static NriPlugins off() {
      return new NriPlugins(Toggle.off());
    }
  }
}
