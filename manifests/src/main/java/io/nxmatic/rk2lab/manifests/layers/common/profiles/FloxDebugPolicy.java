// @codebase
package io.nxmatic.rk2lab.manifests.layers.common.profiles;

/**
 * Synth-scoped debug policy for the flox NRI plugin. Carried on {@link
 * io.nxmatic.rk2lab.manifests.api.ManifestSynthesisRequest} and reachable from any manifest unit
 * via {@link io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit#floxDebugPolicy()}.
 *
 * <p>When {@link #enabled()} is true, layers swap their pod-spec primitives (image, entrypoint,
 * flox env name) to a debug shape so the workload pauses for live inspection while still going
 * through the flox NRI overlay path.
 *
 * <p>This used to be a static singleton populated from a process-wide environment variable. It is
 * now an immutable record built at the entry point that owns the synth (e.g., seed-bootstrap),
 * which gives layer code a single, request-scoped source of truth and lets tests inject a custom
 * shape without mutating global state.
 */
public record FloxDebugPolicy(boolean enabled) {

  /**
   * Single source of truth for the production carrier image. Workload binaries come from the flox
   * env overlay; the carrier just needs a {@code /bin/sh} for {@code flox activate} to bootstrap
   * from (flox shells out to a shell as part of activation). Busybox provides that with applets at
   * {@code /sbin} + {@code /bin}, which the installer appends to {@code
   * NIX_DEFAULT_PROFILE_BIN_STORE_PATH} so they sit at the tail of the runtime PATH.
   */
  private static final String PROD_IMAGE = "busybox:stable";

  private static final String DEBUG_IMAGE = "alpine:latest";
  private static final FloxDebugPolicy DISABLED = new FloxDebugPolicy(false);

  /** Production-shape policy: every primitive falls through unchanged. */
  public static FloxDebugPolicy disabled() {
    return DISABLED;
  }

  /**
   * Debug-shape policy: opts a workload's pod into the shell sidecar (see {@link
   * FloxShellSidecarProfile}). Layers no longer flip their prod container's image/command/flox env;
   * debug capability is additive.
   */
  public static FloxDebugPolicy debug() {
    return new FloxDebugPolicy(true);
  }

  public String debugImage() {
    return DEBUG_IMAGE;
  }

  /** The single prod-image identifier shared by every flox-injected workload carrier. */
  public String prodImage() {
    return PROD_IMAGE;
  }

  /**
   * Returns {@link #prodImage()} unless debug is enabled, in which case {@link #debugImage()}. The
   * one consumer is {@link DelveSidecarProfile}, which wants alpine when delve is opt-in; workload
   * carriers should call {@link #prodImage()} directly and rely on {@link FloxShellSidecarProfile}
   * for the alpine shell.
   */
  public String image() {
    return enabled ? DEBUG_IMAGE : PROD_IMAGE;
  }
}
