package io.nxmatic.rke2lab.manifests.contract.hostasset;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A provider's offer of host assets: the entries, the logical slot they target, the delivery kind
 * incus applies to place them, whether the placed files are executable, and — for a fan-in kind —
 * the single output file it produces. A provider may yield several (the systemd bundle contributes
 * one per slot — units and scripts).
 *
 * <p>{@code targetFile} names the ONE output artifact a fan-in kind writes ({@link
 * HostAssetDeliveryKind#SHELL_ENV_FILE}); the fan-out kinds ({@code SEED_DIR}, {@code
 * CONFIGMAP_FILES}) derive their outputs from the entries and leave it {@link Optional#empty()}.
 *
 * <p>{@code executable} is declared BY THE CONTRIBUTION (the domain knows a systemd script must be
 * runnable, a unit file must not), not inferred by incus from the slot — so incus stays agnostic of
 * its clients. Only a {@link HostAssetDeliveryKind#CONFIGMAP_FILES} contribution may be executable;
 * use {@link #executableFiles} for it.
 */
public record HostAssetContribution(
    HostAssetSlot slot,
    HostAssetDeliveryKind deliveryKind,
    List<HostAssetEntry> entries,
    Optional<String> targetFile,
    boolean executable) {

  public HostAssetContribution {
    slot = Objects.requireNonNull(slot, "slot");
    deliveryKind = Objects.requireNonNull(deliveryKind, "deliveryKind");
    entries = List.copyOf(entries);
    targetFile =
        Objects.requireNonNull(targetFile, "targetFile")
            .map(String::trim)
            .filter(t -> !t.isBlank());
    if (deliveryKind == HostAssetDeliveryKind.SHELL_ENV_FILE && targetFile.isEmpty()) {
      throw new IllegalArgumentException("SHELL_ENV_FILE contribution requires a targetFile");
    }
    if (deliveryKind != HostAssetDeliveryKind.SHELL_ENV_FILE && targetFile.isPresent()) {
      throw new IllegalArgumentException(
          deliveryKind + " does not use a targetFile: " + targetFile.orElseThrow());
    }
    if (executable && deliveryKind != HostAssetDeliveryKind.CONFIGMAP_FILES) {
      throw new IllegalArgumentException(
          deliveryKind + " cannot be executable — only CONFIGMAP_FILES");
    }
  }

  /** A fan-out contribution (SEED_DIR / CONFIGMAP_FILES) whose files are NOT executable. */
  public static HostAssetContribution fanOut(
      HostAssetSlot slot, HostAssetDeliveryKind deliveryKind, List<HostAssetEntry> entries) {
    return new HostAssetContribution(slot, deliveryKind, entries, Optional.empty(), false);
  }

  /**
   * A CONFIGMAP_FILES contribution whose extracted files land executable — the domain declares it
   * (a systemd script must be runnable), incus does not infer it from the slot.
   */
  public static HostAssetContribution executableFiles(
      HostAssetSlot slot, List<HostAssetEntry> entries) {
    return new HostAssetContribution(
        slot, HostAssetDeliveryKind.CONFIGMAP_FILES, entries, Optional.empty(), true);
  }

  /** A SHELL_ENV_FILE contribution: the entries fan into the single {@code targetFile}. */
  public static HostAssetContribution shellEnvFile(
      HostAssetSlot slot, List<HostAssetEntry> entries, String targetFile) {
    return new HostAssetContribution(
        slot, HostAssetDeliveryKind.SHELL_ENV_FILE, entries, Optional.of(targetFile), false);
  }
}
