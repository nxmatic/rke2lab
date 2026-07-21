package io.nxmatic.rke2lab.manifests.contract.hostasset;

/**
 * How incus's materializer places a {@link HostAssetContribution}'s entries into its slot root. The
 * kind is the CONTENT semantics manifests knows (a cloud-config becomes a seed dir, env sections
 * become one shell file); the driver owns the matching write strategy.
 */
public enum HostAssetDeliveryKind {
  /** Strip the ConfigMap/Secret envelope and write each keyed value as a seed file (NoCloud). */
  SEED_DIR,
  /** Copy each entry verbatim under the slot root, honouring the executable bit. */
  DIRECT_COPY,
  /**
   * Extract the env vars from each entry's ConfigMap/Secret and emit ONE shell env file (wrapped
   * {@code set -a}…{@code set +a}) the boot sources — the contribution's {@code targetFile} names
   * it.
   */
  SHELL_ENV_FILE
}
