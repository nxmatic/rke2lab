// @codebase
package io.nxmatic.rk2lab.manifests;

import io.nxmatic.rk2lab.manifests.profiles.BootstrapIdentity;
import io.nxmatic.rk2lab.manifests.profiles.ComponentVersions;
import io.nxmatic.rk2lab.manifests.profiles.FloxDebugPolicy;
import io.nxmatic.rk2lab.manifests.profiles.ImageState;
import io.nxmatic.rk2lab.manifests.profiles.NetworkTopology;
import java.util.List;
import software.constructs.Construct;

public abstract class AbstractManifestUnit extends Construct implements ManifestUnit {

  private final String manifestUnitId;
  private final List<String> dependsOnManifestUnitIds;

  // Old constructor - kept for backward compatibility with existing ManifestUnits
  protected AbstractManifestUnit(
      final String manifestUnitId, final List<String> dependsOnManifestUnitIds) {
    super(null, manifestUnitId); // Null scope for old pattern (will be created via apply())
    this.manifestUnitId = manifestUnitId;
    this.dependsOnManifestUnitIds = List.copyOf(dependsOnManifestUnitIds);
  }

  // New constructor - for merged ManifestUnits that extend Construct
  protected AbstractManifestUnit(
      final Construct scope,
      final String id,
      final String manifestUnitId,
      final List<String> dependsOnManifestUnitIds) {
    super(scope, id);
    this.manifestUnitId = manifestUnitId;
    this.dependsOnManifestUnitIds = List.copyOf(dependsOnManifestUnitIds);
  }

  @Override
  public final String manifestUnitId() {
    return manifestUnitId;
  }

  @Override
  public final List<String> dependsOnManifestUnitIds() {
    return dependsOnManifestUnitIds;
  }

  /**
   * Single accessor for the flox NRI debug toggle. Layers reach this through their owning manifest
   * unit; the policy is published by the synthesizer for the duration of one {@code synthesize}
   * call via {@link ManifestSynthesisContext}.
   */
  protected final FloxDebugPolicy floxDebugPolicy() {
    return ManifestSynthesisContext.current().floxDebugPolicy();
  }

  /** Cluster + node identity slice. */
  protected final BootstrapIdentity bootstrapIdentity() {
    return ManifestSynthesisContext.current().bootstrapIdentity();
  }

  /** Cluster network topology slice (CIDRs, interfaces, gateway addresses). */
  protected final NetworkTopology networkTopology() {
    return ManifestSynthesisContext.current().networkTopology();
  }

  /** Component-version slice (kube-vip, tailscale, …). */
  protected final ComponentVersions componentVersions() {
    return ManifestSynthesisContext.current().componentVersions();
  }

  /** Stage A → Stage B control-node image identity slice (alias, fingerprint, checksum, remote). */
  protected final ImageState imageState() {
    return ManifestSynthesisContext.current().imageState();
  }
}
