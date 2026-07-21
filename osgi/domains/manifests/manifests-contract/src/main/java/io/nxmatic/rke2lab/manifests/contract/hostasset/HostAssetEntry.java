package io.nxmatic.rke2lab.manifests.contract.hostasset;

import java.util.Objects;

/**
 * One file a provider contributes: a slot-relative path plus its text content (raw slice content —
 * incus's {@link HostAssetDeliveryKind} strategy transforms it). All bootstrap host assets are text
 * (shell, yaml, env), so content is a {@code String}; the executable bit is honoured only by {@link
 * HostAssetDeliveryKind#DIRECT_COPY}.
 */
public record HostAssetEntry(String relativePath, String content, boolean executable) {

  public HostAssetEntry {
    relativePath = requireSlotRelative(relativePath);
    content = Objects.requireNonNull(content, "content");
  }

  public static HostAssetEntry file(String relativePath, String content) {
    return new HostAssetEntry(relativePath, content, false);
  }

  public static HostAssetEntry executable(String relativePath, String content) {
    return new HostAssetEntry(relativePath, content, true);
  }

  private static String requireSlotRelative(String relativePath) {
    final String normalized = Objects.requireNonNull(relativePath, "relativePath").trim();
    if (normalized.isBlank()) {
      throw new IllegalArgumentException("relativePath must not be blank");
    }
    if (normalized.startsWith("/")) {
      throw new IllegalArgumentException("relativePath must be slot-relative: " + normalized);
    }
    if (normalized.equals("..") || normalized.startsWith("../") || normalized.contains("/../")) {
      throw new IllegalArgumentException("relativePath must not escape its slot: " + normalized);
    }
    return normalized;
  }
}
