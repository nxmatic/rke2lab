// @codebase
package io.nxmatic.rk2lab.manifests;

import io.nxmatic.rk2lab.manifests.profiles.BootstrapIdentity;
import io.nxmatic.rk2lab.manifests.profiles.ComponentVersions;
import io.nxmatic.rk2lab.manifests.profiles.FloxDebugPolicy;
import io.nxmatic.rk2lab.manifests.profiles.ImageState;
import io.nxmatic.rk2lab.manifests.profiles.NetworkTopology;
import java.util.List;
import software.constructs.Construct;

public abstract class AbstractManifestsUnit extends Construct implements ManifestsUnit {

  private final String manifestUnitId;
  private final List<String> dependsOnManifestsUnitIds;

  // Old constructor - kept for backward compatibility with existing ManifestsUnits
  protected AbstractManifestsUnit(
      final String manifestUnitId, final List<String> dependsOnManifestsUnitIds) {
    super(null, manifestUnitId); // Null scope for old pattern (will be created via apply())
    this.manifestUnitId = manifestUnitId;
    this.dependsOnManifestsUnitIds = List.copyOf(dependsOnManifestsUnitIds);
  }

  // New constructor - for merged ManifestsUnits that extend Construct
  protected AbstractManifestsUnit(
      final Construct scope,
      final String id,
      final String manifestUnitId,
      final List<String> dependsOnManifestsUnitIds) {
    super(scope, id);
    this.manifestUnitId = manifestUnitId;
    this.dependsOnManifestsUnitIds = List.copyOf(dependsOnManifestsUnitIds);
  }

  @Override
  public final String manifestUnitId() {
    return manifestUnitId;
  }

  @Override
  public final List<String> dependsOnManifestsUnitIds() {
    return dependsOnManifestsUnitIds;
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
