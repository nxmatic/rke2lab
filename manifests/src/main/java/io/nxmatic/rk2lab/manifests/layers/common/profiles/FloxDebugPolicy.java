// @codebase
package io.nxmatic.rk2lab.manifests.layers.common.profiles;

import java.util.List;

/**
 * Single source of truth for the flox NRI debug toggle. When
 * RKE2LAB_POLICY_DEBUG_NRI_PLUGINS_FLOX_ENABLED is true, layers can swap their pod-spec primitives
 * (image, entrypoint, flox env name) to a debug shape so the workload pauses for live inspection
 * while still going through the flox NRI overlay path.
 *
 * <p>Reachable from any manifest unit via {@link
 * io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit#floxDebugPolicy()}; also exposed
 * as a static singleton for direct use inside Layers.
 */
public final class FloxDebugPolicy {

  private static final String ENV_VAR = "RKE2LAB_POLICY_DEBUG_NRI_PLUGINS_FLOX_ENABLED";
  private static final String DEBUG_IMAGE = "bash:5";
  private static final List<String> PAUSE_COMMAND = List.of("sleep", "infinity");

  private static final FloxDebugPolicy INSTANCE =
      new FloxDebugPolicy("true".equalsIgnoreCase(System.getenv(ENV_VAR)));

  private final boolean enabled;

  private FloxDebugPolicy(final boolean enabled) {
    this.enabled = enabled;
  }

  public static FloxDebugPolicy get() {
    return INSTANCE;
  }

  public boolean enabled() {
    return enabled;
  }

  public String debugImage() {
    return DEBUG_IMAGE;
  }

  public List<String> pauseCommand() {
    return PAUSE_COMMAND;
  }

  /** Returns {@code prodImage} unless debug is enabled, in which case {@link #debugImage()}. */
  public String image(final String prodImage) {
    return enabled ? DEBUG_IMAGE : prodImage;
  }

  /** Returns {@code prodCommand} unless debug is enabled, in which case {@link #pauseCommand()}. */
  public List<String> command(final List<String> prodCommand) {
    return enabled ? PAUSE_COMMAND : prodCommand;
  }

  /**
   * Returns {@code prodEnv} unless debug is enabled and a {@code debugEnv} variant is supplied.
   * Used to swap to {@code <category>/<name>-debug} flox envs that ship dlv and shell tools.
   */
  public String floxEnvironment(final String prodEnv, final String debugEnv) {
    return (enabled && debugEnv != null) ? debugEnv : prodEnv;
  }
}
