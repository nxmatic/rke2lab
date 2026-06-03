// @codebase
package io.nxmatic.rk2lab.manifests.profiles;

/**
 * Synth-scoped debug policy aligned by manifest domain. Carried on {@link
 * io.nxmatic.rk2lab.manifests.ManifestSynthesisRequest} and reachable from any manifest unit via
 * {@link io.nxmatic.rk2lab.manifests.AbstractManifestsUnit#floxDebugPolicy()}.
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
   * Single source of truth for the production carrier image. Workload binaries come from the flox
   * env overlay; the carrier just needs a {@code /bin/sh} for {@code flox activate} to bootstrap
   * from.
   */
  private static final String PROD_IMAGE = "busybox:stable";

  private static final String DEBUG_IMAGE = "alpine:latest";
  private static final FloxDebugPolicy DISABLED = new FloxDebugPolicy(false, false, false);

  /** Production-shape policy: every primitive falls through unchanged. */
  public static FloxDebugPolicy disabled() {
    return DISABLED;
  }

  public String debugImage() {
    return DEBUG_IMAGE;
  }

  /** The single prod-image identifier shared by every flox-injected workload carrier. */
  public String prodImage() {
    return PROD_IMAGE;
  }

  /** True if any per-domain debug toggle is on (used by the shell sidecar profile). */
  public boolean anyDomainEnabled() {
    return meshEnabled || networkingEnabled;
  }

  /**
   * Returns {@link #prodImage()} unless any domain debug is enabled, in which case {@link
   * #debugImage()}. The one consumer is {@link DelveSidecarProfile}.
   */
  public String image() {
    return anyDomainEnabled() ? DEBUG_IMAGE : PROD_IMAGE;
  }

  /**
   * Selects the flox env mounted into the prod container for the given domain. When the matching
   * domain toggle is on, prod runs against the debug env (unstripped binary, delve in PATH) so the
   * shell sidecar can {@code dlv attach $(pgrep ...)} with source-level visibility. When off, prod
   * stays on its production env.
   */
  public String resolveMeshEnvironment(final String prodEnv, final String debugEnv) {
    return meshEnabled ? debugEnv : prodEnv;
  }

  public String resolveNetworkingEnvironment(final String prodEnv, final String debugEnv) {
    return networkingEnabled ? debugEnv : prodEnv;
  }
}
