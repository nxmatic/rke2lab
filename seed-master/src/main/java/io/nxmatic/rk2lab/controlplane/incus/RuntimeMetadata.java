package io.nxmatic.rk2lab.controlplane.incus;

import java.util.Map;

/**
 * Runtime metadata — configuration summaries for runtime components.
 *
 * @param environment layer environment registry state
 * @param systemd systemd provisioning state (scripts, units)
 */
public record RuntimeMetadata(Environment environment, Systemd systemd) {

  /**
   * Layer environment registry state.
   *
   * <p>Tracks registered Flox environments and their configurations.
   *
   * @param summary environment registry summary map
   */
  public record Environment(Map<String, Object> summary) {

    public static Environment of(Map<String, Object> summary) {
      return new Environment(Map.copyOf(summary));
    }
  }

  /**
   * Systemd provisioning state.
   *
   * <p>Summarizes systemd units, scripts, and libexec contributions.
   *
   * @param summary systemd provisioning summary map
   */
  public record Systemd(Map<String, Object> summary) {

    public static Systemd of(Map<String, Object> summary) {
      return new Systemd(Map.copyOf(summary));
    }
  }
}
