// @codebase
package io.seedmatic.rke2lab.manifests.contract.profiles;

/**
 * Synth-scoped debug policy aligned by manifest domain. Carried on {@link
 * io.seedmatic.rke2lab.manifests.contract.ManifestSynthesisRequest} and reachable from any manifest
 * unit via {@link io.seedmatic.rke2lab.manifests.AbstractManifestsUnit#floxDebugPolicy()}.
 *
 * <p>Three independent toggles:
 *
 * <ul>
 *   <li>{@code meshEnabled} — gates shell sidecar + debug-env flip on every workload in the {@code
 *       mesh/*} flox env namespace (headscale, headplane, tailscale-gateway, headscale-client).
 *   <li>{@code networkingEnabled} — gates the same on {@code networking/*} workloads (kdns).
 *   <li>{@code floxNriPluginEnabled} — gates the NRI plugin daemon's *own* debug build (the {@code
 *       flox-nri-plugin-debug} derivation in the runtime flake). The plugin is the carrier, not a
 *       workload, so it has its own toggle independent of the per-domain ones.
 * </ul>
 *
 * <p>Each domain toggle, when on, asks layers in that domain to:
 *
 * <ol>
 *   <li>Mount the {@code <domain>/<workload>-debug} flox env in the prod container (delivers an
 *       unstripped binary built with {@code -N -l} + delve in PATH).
 *   <li>Add a {@code <workload>-shell} sidecar with {@code SYS_PTRACE} sharing the prod mounts.
 *   <li>Set {@code shareProcessNamespace: true} on the pod so the sidecar can {@code dlv attach
 *       $(pgrep <workload>)}.
 * </ol>
 */
public record FloxDebugPolicy(
    boolean meshEnabled, boolean networkingEnabled, boolean floxNriPluginEnabled) {

  /**
   * Single source of truth for the live carrier image — the minimal nix-built OCI base every
   * flox-injected pod runs (nixos/flox-carrier.nix, baked into the node image and auto-imported by
   * rke2's air-gap path, so {@code imagePullPolicy: IfNotPresent} never pulls). Workload binaries
   * come from the flox env overlay; the carrier just provides {@code /usr/bin/env} + a shell for
   * {@code flox activate} to bootstrap from. The prod/debug distinction now lives in the flox ENV
   * (unstripped binary + delve), not the base image, so prod and debug share this one carrier. The
   * string MUST match the RepoTag the nix image is tagged with.
   */
  private static final String CARRIER_IMAGE = "rke2lab/flox-carrier:0.1.0";

  private static final FloxDebugPolicy DISABLED = new FloxDebugPolicy(false, false, false);

  /** Live-shape policy: every primitive falls through unchanged. */
  public static FloxDebugPolicy disabled() {
    return DISABLED;
  }

  public String debugImage() {
    return CARRIER_IMAGE;
  }

  /** The single prod-image identifier shared by every flox-injected workload carrier. */
  public String prodImage() {
    return CARRIER_IMAGE;
  }

  /** True if any per-domain debug toggle is on (used by the shell sidecar profile). */
  public boolean anyDomainEnabled() {
    return meshEnabled || networkingEnabled;
  }

  /**
   * The carrier image. Prod and debug now share the single nix carrier — the debug affordance
   * (unstripped binary + delve) lives in the flox env, not the base image — so this no longer
   * branches on {@link #anyDomainEnabled()}. The one consumer is {@link DelveSidecarProfile}.
   */
  public String image() {
    return CARRIER_IMAGE;
  }

  /**
   * Selects the flox env mounted into the prod container for the given domain. When the matching
   * domain toggle is on, prod runs against the debug env (unstripped binary, delve in PATH) so the
   * shell sidecar can {@code dlv attach $(pgrep ...)} with source-level visibility. When off, prod
   * stays on its live env.
   */
  public String resolveMeshEnvironment(final String prodEnv, final String debugEnv) {
    return meshEnabled ? debugEnv : prodEnv;
  }

  public String resolveNetworkingEnvironment(final String prodEnv, final String debugEnv) {
    return networkingEnabled ? debugEnv : prodEnv;
  }
}
