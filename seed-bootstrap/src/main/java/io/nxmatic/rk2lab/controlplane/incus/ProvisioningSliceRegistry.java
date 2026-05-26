package io.nxmatic.rk2lab.controlplane.incus;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry for provisioning slices — independently reconcilable resource partitions.
 *
 * <p>Components register their paths during materialization; the registry then drives per-slice
 * checksum computation. Slice changes trigger slice-specific reconciliation without full master
 * renewal.
 */
public final class ProvisioningSliceRegistry {

  private final Map<String, List<Path>> sliceRoots = new LinkedHashMap<>();

  public ProvisioningSliceRegistry() {}

  /**
   * Register one or more filesystem roots as belonging to the named slice.
   *
   * <p>Multiple calls with the same slice name accumulate; the slice checksum covers all registered
   * roots.
   *
   * @param sliceName logical slice identifier (e.g. "floxRuntime", "kdns")
   * @param roots filesystem paths to include in this slice's checksum
   */
  public void register(String sliceName, Path... roots) {
    if (sliceName == null || sliceName.isBlank()) {
      throw new IllegalArgumentException("Slice name must not be null or blank");
    }
    if (roots == null || roots.length == 0) {
      throw new IllegalArgumentException("At least one root path is required");
    }
    sliceRoots.computeIfAbsent(sliceName, k -> new ArrayList<>()).addAll(List.of(roots));
  }

  /**
   * Returns an immutable view of registered slices and their roots.
   *
   * @return map from slice name to list of filesystem roots
   */
  public Map<String, List<Path>> getSliceRoots() {
    // Deep copy to preserve immutability
    final LinkedHashMap<String, List<Path>> copy = new LinkedHashMap<>();
    for (Map.Entry<String, List<Path>> entry : sliceRoots.entrySet()) {
      copy.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    return Map.copyOf(copy);
  }

  /**
   * Returns true if the named slice has been registered.
   *
   * @param sliceName slice to check
   * @return true if at least one root is registered for this slice
   */
  public boolean hasSlice(String sliceName) {
    return sliceRoots.containsKey(sliceName);
  }
}
