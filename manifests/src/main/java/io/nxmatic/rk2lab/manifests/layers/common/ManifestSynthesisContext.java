// @codebase
package io.nxmatic.rk2lab.manifests.layers.common;

import io.nxmatic.rk2lab.manifests.layers.common.profiles.BootstrapIdentity;
import io.nxmatic.rk2lab.manifests.layers.common.profiles.ComponentVersions;
import io.nxmatic.rk2lab.manifests.layers.common.profiles.FloxDebugPolicy;
import io.nxmatic.rk2lab.manifests.layers.common.profiles.NetworkTopology;
import java.util.Objects;

/**
 * Synthesis-scoped context exposing per-synth policies and identity data to layer code. The
 * synthesizer publishes the context for the duration of {@code synthesize(...)} and resets it on
 * exit; layers reach it through {@link AbstractManifestUnit} accessors without keeping a static
 * dependency on a process-wide singleton.
 *
 * <p>The context composes orthogonal slices — each slice is its own record so adding fields to one
 * concern doesn't churn the others, and so layers can reach only for what they need:
 *
 * <ul>
 *   <li>{@link FloxDebugPolicy} — flox NRI debug toggle (image / command / env swap).
 *   <li>{@link BootstrapIdentity} — cluster + node identity (cluster name, id, token, …).
 *   <li>{@link NetworkTopology} — CIDRs, interface names, gateway addresses.
 *   <li>{@link ComponentVersions} — kube-vip, tailscale, envoy-gateway, … versions.
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
          ComponentVersions.empty());

  private static final ThreadLocal<ManifestSynthesisContext> CURRENT = new ThreadLocal<>();

  private final FloxDebugPolicy floxDebugPolicy;
  private final BootstrapIdentity bootstrapIdentity;
  private final NetworkTopology networkTopology;
  private final ComponentVersions componentVersions;

  private ManifestSynthesisContext(
      FloxDebugPolicy floxDebugPolicy,
      BootstrapIdentity bootstrapIdentity,
      NetworkTopology networkTopology,
      ComponentVersions componentVersions) {
    this.floxDebugPolicy = Objects.requireNonNull(floxDebugPolicy, "floxDebugPolicy");
    this.bootstrapIdentity = Objects.requireNonNull(bootstrapIdentity, "bootstrapIdentity");
    this.networkTopology = Objects.requireNonNull(networkTopology, "networkTopology");
    this.componentVersions = Objects.requireNonNull(componentVersions, "componentVersions");
  }

  public static ManifestSynthesisContext of(
      FloxDebugPolicy floxDebugPolicy,
      BootstrapIdentity bootstrapIdentity,
      NetworkTopology networkTopology,
      ComponentVersions componentVersions) {
    return new ManifestSynthesisContext(
        floxDebugPolicy, bootstrapIdentity, networkTopology, componentVersions);
  }

  /** Convenience overload for callers that only need to override the flox debug policy. */
  public static ManifestSynthesisContext of(FloxDebugPolicy floxDebugPolicy) {
    return new ManifestSynthesisContext(
        floxDebugPolicy,
        BootstrapIdentity.unknown(),
        NetworkTopology.empty(),
        ComponentVersions.empty());
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
