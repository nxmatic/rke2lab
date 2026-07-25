package io.nxmatic.rke2lab.manifests.contract;

import io.nxmatic.rke2lab.seed.broker.port.Amendment;
import io.nxmatic.rke2lab.seed.broker.port.SeedContract;
import java.util.Optional;

/**
 * The wire contract for the manifests {@code runbook} trigger — the activation payload a sower must
 * supply to play the manifests scion. It is the INPUT twin of a reaped wire-record: the {@code
 * shape} meta-coordinate projects THIS record's JSON Schema so a sower learns the shape from the
 * broker door rather than compiling the class (see docs/architecture/osgi/seed-broker-spec.adoc §
 * introspection).
 *
 * <p>Its components carry three kinds of {@link Amendment}, each a SINGLE field its role binds by
 * value (the amont mapping the schema alone cannot express — see seed-broker-spec § @Amendment):
 *
 * <ul>
 *   <li>{@link Amendment#FACET} — {@link #facets} is the WHOLE {@code rke2lab:manifests:} concern
 *       map of {@code Pulumi.dev.yaml} ({@code {publish, debug}}), one composite component so the
 *       role binds to ONE field. The host contributes the yaml subtree verbatim as the FACET value
 *       (an {@link io.nxmatic.rke2lab.seed.broker.port.AmendmentContributor} it registers into the
 *       world); the assembler gathers it at the amend door and the binder places it on {@code
 *       facets}, naming no manifests vocabulary — {@link Facets} mirroring the yaml is what keeps
 *       the copy blind. When no contributor offers FACET (a bare {@code shape} probe, a survey), it
 *       falls to {@link #defaults()}.
 *   <li>{@link Amendment#SOIL} — {@link #materializationRoot} is NOT in the yaml: it is the plot
 *       the scion materialises into, which only the host knows (it holds {@code BootstrapPaths}).
 *       The host fills it by role — the SOIL amendment — from its provisioning state (the
 *       staging-view {@code manifestsRoot}), never from a yaml key. Blank when unamended (a bare
 *       {@code shape} probe, or a survey run) — the scion then materialises into a temp dir.
 *   <li>{@link Amendment#WORKTREE} — {@link #worktree} carries the cluster/node identity (see the
 *       {@code Worktree} note below).
 * </ul>
 *
 * <p>Because the host only fills amendments by role, ALL the domain knowledge lives in the scion
 * (OSGi-side): it decodes this record (jackson coerces the yaml's string {@code "true"} to {@code
 * boolean}), flattens the nesting, and translates into its own vocabulary — {@link
 * ManifestDomainPolicy} (synth-time filter) + {@code FloxDebugPolicy} (per-layer debug) + the
 * {@code RKE2LAB_MANIFESTS_PUBLISH_*} publish-time env contributions. The host names no {@code
 * manifests.contract} translation type.
 */
@SeedContract("runbook")
public record ManifestsRunbookInput(
    @Amendment(Amendment.FACET) Facets facets,
    @Amendment(Amendment.SOIL) String materializationRoot,
    @Amendment(Amendment.WORKTREE) Optional<Worktree> worktree) {

  /**
   * The complete facet with every concern at its default — the operator's usual posture, debug off,
   * and an UNAMENDED soil (blank {@code materializationRoot} → the scion surveys into a temp dir).
   * The seed a scion holds before a sow arrives (never a partial instance): every component is a
   * complete sub-facet, so no incomplete state ever exists.
   */
  public static ManifestsRunbookInput defaults() {
    return new ManifestsRunbookInput(Facets.defaults(), "", Optional.empty());
  }

  /**
   * The {@code rke2lab:manifests:} concern map, mirroring its yaml sub-map EXACTLY ({@code
   * {publish, debug}}) — the single {@link Amendment#FACET} component so the role binds to ONE
   * field (the binder rejects a role borne by several components as ambiguous). The host
   * contributes this whole subtree verbatim; the scion reads {@code facets().publish()} / {@code
   * facets().debug()} and flattens each.
   */
  public record Facets(PublishFacet publish, DebugFacet debug) {

    public static Facets defaults() {
      return new Facets(PublishFacet.defaults(), DebugFacet.disabled());
    }
  }

  /**
   * The cluster/node identity the gardener hands over as the {@link Amendment#WORKTREE} amendment —
   * the same neutral provisioning-scalar role the incus scion reconstructs its topology from. The
   * manifests scion needs only the identity subset (cluster + node name); the synthesis derives the
   * whole network topology from the cluster name (a pure function — see {@code
   * ClusterNetworkBlueprint.deriveRecipeModel}). A blind subtree mirroring the WORKTREE schema,
   * naming no other domain's type; the host's extra worktree scalars are ignored on decode. EMPTY =
   * unamended (a bare survey / the direct CLI call) — the synthesis falls back to an unknown
   * identity.
   */
  public record Worktree(String clusterName, String nodeName) {}

  /**
   * The {@code manifests.publish} concern: which domain manifest layers the master publishes into
   * RKE2's server-manifests directory (a symlink/stow it auto-applies). Flat booleans, mirroring
   * the yaml sub-map exactly; the scion feeds them to {@link ManifestDomainPolicy.Builder} and the
   * {@code RKE2LAB_MANIFESTS_PUBLISH_*} overlay. An absent key defaults to the operator's usual
   * posture (everything on except {@code mesh}) so a partial yaml still yields a complete facet.
   */
  public record PublishFacet(
      boolean gitops,
      boolean networking,
      boolean clusterApi,
      boolean storage,
      boolean mesh,
      boolean highAvailability,
      boolean cicd) {

    public static PublishFacet defaults() {
      return new PublishFacet(true, true, true, true, false, true, true);
    }
  }

  /**
   * The {@code manifests.debug} concern, mirroring the yaml's {@code enabled}-wrapper nesting
   * exactly ({@code debug.mesh.enabled}, {@code debug.networking.enabled}, {@code
   * debug.nriPlugins.flox.enabled}). The scion flattens it into the three booleans {@code
   * FloxDebugPolicy} carries. An absent toggle defaults to off.
   */
  public record DebugFacet(Toggle mesh, Toggle networking, NriPlugins nriPlugins) {

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

    public static NriPlugins off() {
      return new NriPlugins(Toggle.off());
    }
  }
}
