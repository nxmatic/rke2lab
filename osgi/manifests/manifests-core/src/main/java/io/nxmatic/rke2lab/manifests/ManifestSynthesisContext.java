// @codebase
package io.nxmatic.rke2lab.manifests;

import io.nxmatic.rke2lab.manifests.port.ManifestSynthesisRequest;
import io.nxmatic.rke2lab.manifests.port.profiles.BootstrapIdentity;
import io.nxmatic.rke2lab.manifests.port.profiles.ComponentVersions;
import io.nxmatic.rke2lab.manifests.port.profiles.FloxDebugPolicy;
import io.nxmatic.rke2lab.manifests.port.profiles.ImageState;
import io.nxmatic.rke2lab.manifests.port.profiles.IncusIdentityMaterial;
import io.nxmatic.rke2lab.manifests.port.profiles.NetworkTopology;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Synthesis-scoped context exposing per-synth policies and identity data to layer code. The
 * synthesizer publishes the context for the duration of {@code synthesize(...)} and resets it on
 * exit; layers reach it through {@link AbstractManifestsUnit} accessors without keeping a static
 * dependency on a process-wide singleton.
 *
 * <p>The context wraps the {@link ManifestSynthesisRequest} (the host→OSGi frontier type) and
 * delegates each synthesis slice to it, so a new slice on the request needs no change here. Layers
 * reach only for what they need:
 *
 * <ul>
 *   <li>{@link FloxDebugPolicy} — flox NRI debug toggle (image / command / env swap).
 *   <li>{@link BootstrapIdentity} — cluster + node identity (cluster name, id, token, Incus
 *       remote/identity, …).
 *   <li>{@link NetworkTopology} — CIDRs, interface names, gateway addresses.
 *   <li>{@link ComponentVersions} — kube-vip, tailscale, envoy-gateway, … versions.
 *   <li>{@link ImageState} — Stage A → Stage B control-node image identity for the image-state
 *       ConfigMap.
 *   <li>{@link IncusIdentityMaterial} — Stage A → Stage B Incus identity for the identity Secret.
 * </ul>
 *
 * <p>When no synthesis is in progress (direct unit tests of a Layer without {@code synthesize}),
 * {@link #current()} returns a default context whose slices are all-disabled / unknown / empty.
 */
public final class ManifestSynthesisContext {

  private static final ManifestSynthesisContext DEFAULT =
      new ManifestSynthesisContext(
          ManifestSynthesisRequest.builder(Path.of("."), Path.of("manifests.yaml")).build());

  private static final ThreadLocal<ManifestSynthesisContext> CURRENT = new ThreadLocal<>();

  private final ManifestSynthesisRequest request;

  private ManifestSynthesisContext(ManifestSynthesisRequest request) {
    this.request = Objects.requireNonNull(request, "request");
  }

  public static ManifestSynthesisContext of(ManifestSynthesisRequest request) {
    return new ManifestSynthesisContext(request);
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
    return request.floxDebugPolicy();
  }

  public BootstrapIdentity bootstrapIdentity() {
    return request.bootstrapIdentity();
  }

  public NetworkTopology networkTopology() {
    return request.networkTopology();
  }

  public ComponentVersions componentVersions() {
    return request.componentVersions();
  }

  public ImageState imageState() {
    return request.imageState();
  }

  public IncusIdentityMaterial incusIdentity() {
    return request.incusIdentity();
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
