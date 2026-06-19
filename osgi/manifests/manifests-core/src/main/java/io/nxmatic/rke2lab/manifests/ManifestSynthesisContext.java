// @codebase
package io.nxmatic.rke2lab.manifests;

import io.nxmatic.rke2lab.manifests.bridge.profiles.BootstrapIdentity;
import io.nxmatic.rke2lab.manifests.bridge.profiles.ComponentVersions;
import io.nxmatic.rke2lab.manifests.bridge.profiles.FloxDebugPolicy;
import io.nxmatic.rke2lab.manifests.bridge.profiles.ImageState;
import io.nxmatic.rke2lab.manifests.bridge.profiles.NetworkTopology;
import java.util.Objects;

/**
 * Synthesis-scoped context exposing per-synth policies and identity data to layer code. The
 * synthesizer publishes the context for the duration of {@code synthesize(...)} and resets it on
 * exit; layers reach it through {@link AbstractManifestsUnit} accessors without keeping a static
 * dependency on a process-wide singleton.
 *
 * <p>The context composes orthogonal slices — each slice is its own record so adding fields to one
 * concern doesn't churn the others, and so layers can reach only for what they need:
 *
 * <ul>
 *   <li>{@link FloxDebugPolicy} — flox NRI debug toggle (image / command / env swap).
 *   <li>{@link BootstrapIdentity} — cluster + node identity (cluster name, id, token, Incus
 *       remote/identity, …).
 *   <li>{@link NetworkTopology} — CIDRs, interface names, gateway addresses.
 *   <li>{@link ComponentVersions} — kube-vip, tailscale, envoy-gateway, … versions.
 *   <li>{@link ImageState} — Stage A → Stage B control-node image identity (alias, fingerprint,
 *       build checksum, Incus project/remote) for the image-state ConfigMap.
 * </ul>
 *
 * <p>When no synthesis is in progress (direct unit tests of a Layer without {@code synthesize}),
 * {@link #current()} returns a default context with all-disabled / unknown / empty slices.
 */
public final class ManifestSynthesisContext {

  private static final ManifestSynthesisContext DEFAULT =
      new ManifestSynthesisContext(
          FloxDebugPolicy.disabled(),
          BootstrapIdentity.unknown(),
          NetworkTopology.empty(),
          ComponentVersions.empty(),
          ImageState.unknown());

  private static final ThreadLocal<ManifestSynthesisContext> CURRENT = new ThreadLocal<>();

  private final FloxDebugPolicy floxDebugPolicy;
  private final BootstrapIdentity bootstrapIdentity;
  private final NetworkTopology networkTopology;
  private final ComponentVersions componentVersions;
  private final ImageState imageState;

  private ManifestSynthesisContext(
      FloxDebugPolicy floxDebugPolicy,
      BootstrapIdentity bootstrapIdentity,
      NetworkTopology networkTopology,
      ComponentVersions componentVersions,
      ImageState imageState) {
    this.floxDebugPolicy = Objects.requireNonNull(floxDebugPolicy, "floxDebugPolicy");
    this.bootstrapIdentity = Objects.requireNonNull(bootstrapIdentity, "bootstrapIdentity");
    this.networkTopology = Objects.requireNonNull(networkTopology, "networkTopology");
    this.componentVersions = Objects.requireNonNull(componentVersions, "componentVersions");
    this.imageState = Objects.requireNonNull(imageState, "imageState");
  }

  public static ManifestSynthesisContext of(
      FloxDebugPolicy floxDebugPolicy,
      BootstrapIdentity bootstrapIdentity,
      NetworkTopology networkTopology,
      ComponentVersions componentVersions,
      ImageState imageState) {
    return new ManifestSynthesisContext(
        floxDebugPolicy, bootstrapIdentity, networkTopology, componentVersions, imageState);
  }

  /** Convenience overload for callers that only need to override the flox debug policy. */
  public static ManifestSynthesisContext of(FloxDebugPolicy floxDebugPolicy) {
    return new ManifestSynthesisContext(
        floxDebugPolicy,
        BootstrapIdentity.unknown(),
        NetworkTopology.empty(),
        ComponentVersions.empty(),
        ImageState.unknown());
  }

  public static ManifestSynthesisContext current() {
    final ManifestSynthesisContext current = CURRENT.get();
    return current != null ? current : DEFAULT;
  }

  /**
   * Publishes {@code context} for the calling thread. Returns an {@link AutoCloseable} that
   * restores the previous binding (which may be the default) when closed — wrap in
   * try-with-resources to keep the scope tight and exception-safe.
   */
  public static Scope bind(ManifestSynthesisContext context) {
    final ManifestSynthesisContext previous = CURRENT.get();
    CURRENT.set(Objects.requireNonNull(context, "context"));
    return new Scope(previous);
  }

  public FloxDebugPolicy floxDebugPolicy() {
    return floxDebugPolicy;
  }

  public BootstrapIdentity bootstrapIdentity() {
    return bootstrapIdentity;
  }

  public NetworkTopology networkTopology() {
    return networkTopology;
  }

  public ComponentVersions componentVersions() {
    return componentVersions;
  }

  public ImageState imageState() {
    return imageState;
  }

  /** Restores the previous binding when closed. */
  public static final class Scope implements AutoCloseable {
    private final ManifestSynthesisContext previous;

    private Scope(ManifestSynthesisContext previous) {
      this.previous = previous;
    }

    @Override
    public void close() {
      if (previous == null) {
        CURRENT.remove();
      } else {
        CURRENT.set(previous);
      }
    }
  }
}
