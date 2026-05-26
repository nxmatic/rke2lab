// @codebase
package io.nxmatic.rk2lab.manifests.layers.common.profiles;

import java.util.Map;

/**
 * Component-version slice published to synth-time layers via {@link
 * io.nxmatic.rk2lab.manifests.layers.common.ManifestSynthesisContext}. Replaces the kpt-setter
 * {@code ${kube-vip-version}} / {@code ${tailscale-version}} / {@code ${envoy-gateway-version}}
 * placeholders the deprecated branch carried through {@code apply-setters}.
 *
 * <p>Backed by a {@link Map} so adding a component is a one-line change at the call site that
 * builds the synth request — no record schema growth. Layers reach for known keys; missing keys
 * resolve to the empty string (callers may surface that as an explicit error if the layer demands
 * the version).
 *
 * <p>Until Pulumi config wires component versions through, this slice stays empty in production.
 * The placeholder is in place so layers that should consume versions can do so without refactoring
 * later.
 */
public record ComponentVersions(Map<String, String> versions) {

  private static final ComponentVersions DEFAULT = new ComponentVersions(Map.of());

  public ComponentVersions {
    versions = versions == null ? Map.of() : Map.copyOf(versions);
  }

  /** Empty versions map — production callers populate via Pulumi config when they're ready. */
  public static ComponentVersions empty() {
    return DEFAULT;
  }

  /** Returns the version for {@code component}, or the empty string if unset. */
  public String versionOf(String component) {
    return versions.getOrDefault(component, "");
  }
}
