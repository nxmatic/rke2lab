package io.nxmatic.rke2lab.manifests.contract.hostasset;

import java.util.List;
import java.util.Objects;

/**
 * A provider's offer of host assets: the entries, the logical slot they target, the delivery kind
 * incus applies to place them, and — for a fan-in kind — the single output file it produces. A
 * provider may yield several (the systemd bundle contributes one per slot — units and scripts).
 *
 * <p>{@code targetFile} names the ONE output artifact a fan-in kind writes ({@link
 * HostAssetDeliveryKind#SHELL_ENV_FILE}); the fan-out kinds ({@code SEED_DIR}, {@code
 * CONFIGMAP_FILES}) derive their outputs from the entries and leave it blank.
 */
public record HostAssetContribution(
    HostAssetSlot slot,
    HostAssetDeliveryKind deliveryKind,
    List<HostAssetEntry> entries,
    String targetFile) {

  public HostAssetContribution {
    slot = Objects.requireNonNull(slot, "slot");
    deliveryKind = Objects.requireNonNull(deliveryKind, "deliveryKind");
    entries = List.copyOf(entries);
    targetFile = targetFile == null ? "" : targetFile.trim();
    if (deliveryKind == HostAssetDeliveryKind.SHELL_ENV_FILE && targetFile.isBlank()) {
      throw new IllegalArgumentException("SHELL_ENV_FILE contribution requires a targetFile");
    }
    if (deliveryKind != HostAssetDeliveryKind.SHELL_ENV_FILE && !targetFile.isBlank()) {
      throw new IllegalArgumentException(
          deliveryKind + " does not use a targetFile: " + targetFile);
    }
  }

  /** A fan-out contribution (SEED_DIR / CONFIGMAP_FILES): the entries drive the outputs. */
  public static HostAssetContribution fanOut(
      HostAssetSlot slot, HostAssetDeliveryKind deliveryKind, List<HostAssetEntry> entries) {
    return new HostAssetContribution(slot, deliveryKind, entries, "");
  }

  /** A SHELL_ENV_FILE contribution: the entries fan into the single {@code targetFile}. */
  public static HostAssetContribution shellEnvFile(
      HostAssetSlot slot, List<HostAssetEntry> entries, String targetFile) {
    return new HostAssetContribution(
        slot, HostAssetDeliveryKind.SHELL_ENV_FILE, entries, targetFile);
  }
}
