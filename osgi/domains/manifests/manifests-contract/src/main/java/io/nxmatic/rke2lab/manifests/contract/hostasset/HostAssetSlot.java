package io.nxmatic.rke2lab.manifests.contract.hostasset;

/**
 * The LOGICAL destination a {@link HostAssetContribution} targets. Manifests names the slot, never
 * a host path — incus maps each slot to a {@code BootstrapPaths} staging root it owns, so the
 * domain stays ignorant of {@code /srv/host/…}.
 */
public enum HostAssetSlot {
  /** The NoCloud seed the instance reads at first boot ({@code user-data}/{@code meta-data}/…). */
  CLOUD_SEED,
  /** The runtime env-config shell file(s) the boot sources. */
  ENV_CONFIG,
  /** The systemd unit files. */
  SYSTEMD_UNITS,
  /** The systemd libexec scripts the units' {@code ExecStart} resolve. */
  SYSTEMD_SCRIPTS
}
