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

  private final Map<String, SliceStoragePolicy> sliceDefinitions = new LinkedHashMap<>();
  private final Map<String, List<Path>> sliceRoots = new LinkedHashMap<>();
  private final Map<Path, String> pathOwnership = new LinkedHashMap<>();

  public ProvisioningSliceRegistry() {}

  /**
   * Define a slice with its storage policy.
   *
   * <p>Must be called before components register in this slice.
   *
   * @param sliceName slice identifier (e.g. "core", "daemonset")
   * @param policy storage policy determining lifecycle behavior
   * @throws IllegalStateException if slice already defined
   */
  public void defineSlice(String sliceName, SliceStoragePolicy policy) {
    if (sliceName == null || sliceName.isBlank()) {
      throw new IllegalArgumentException("Slice name must not be null or blank");
    }
    if (policy == null) {
      throw new IllegalArgumentException("Storage policy must not be null");
    }
    if (sliceDefinitions.containsKey(sliceName)) {
      throw new IllegalStateException("Slice already defined: " + sliceName);
    }
    sliceDefinitions.put(sliceName, policy);
  }

  /**
   * Register a slice component.
   *
   * <p>The slice must have materialized its resources before registration. The registry extracts
   * the slice's name, policy, and materialized paths.
   *
   * @param slice the slice component to register
   * @throws IllegalStateException if slice with same name already registered
   * @throws IllegalArgumentException if path conflicts with existing slice
   */
  public void register(ProvisioningSlice slice) {
    if (slice == null) {
      throw new IllegalArgumentException("Slice must not be null");
    }

    final String sliceName = slice.name();
    final SliceStoragePolicy policy = slice.storagePolicy();
    final List<Path> paths = slice.getMaterializedPaths();

    if (sliceName == null || sliceName.isBlank()) {
      throw new IllegalArgumentException("Slice name must not be null or blank");
    }
    if (policy == null) {
      throw new IllegalArgumentException("Slice storage policy must not be null");
    }
    if (sliceDefinitions.containsKey(sliceName)) {
      throw new IllegalStateException("Slice already registered: " + sliceName);
    }

    // Register policy
    sliceDefinitions.put(sliceName, policy);

    // Register paths if any
    if (paths != null && !paths.isEmpty()) {
      // Enforce single-slice ownership per path
      for (Path root : paths) {
        final String existingOwner = pathOwnership.get(root);
        if (existingOwner != null && !existingOwner.equals(sliceName)) {
          throw new IllegalArgumentException(
              "Path already registered in slice '"
                  + existingOwner
                  + "': "
                  + root
                  + " (attempted to register in '"
                  + sliceName
                  + "')");
        }
        pathOwnership.put(root, sliceName);
      }

      sliceRoots.put(sliceName, new ArrayList<>(paths));
    }
  }

  /**
   * Register one or more filesystem roots as belonging to the named slice.
   *
   * <p>Multiple calls with the same slice name accumulate; the slice checksum covers all registered
   * roots.
   *
   * @param sliceName logical slice identifier (e.g. "core", "daemonset")
   * @param roots filesystem paths to include in this slice's checksum
   * @throws IllegalArgumentException if slice not defined or path already registered elsewhere
   * @deprecated Use {@link #register(ProvisioningSlice)} instead
   */
  @Deprecated
  public void register(String sliceName, Path... roots) {
    if (sliceName == null || sliceName.isBlank()) {
      throw new IllegalArgumentException("Slice name must not be null or blank");
    }
    if (!sliceDefinitions.containsKey(sliceName)) {
      throw new IllegalArgumentException("Slice not defined: " + sliceName);
    }
    if (roots == null || roots.length == 0) {
      throw new IllegalArgumentException("At least one root path is required");
    }

    // Enforce single-slice ownership per path
    for (Path root : roots) {
      final String existingOwner = pathOwnership.get(root);
      if (existingOwner != null && !existingOwner.equals(sliceName)) {
        throw new IllegalArgumentException(
            "Path already registered in slice '"
                + existingOwner
                + "': "
                + root
                + " (attempted to register in '"
                + sliceName
                + "')");
      }
      pathOwnership.put(root, sliceName);
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

  /**
   * Returns the storage policy for the named slice.
   *
   * @param sliceName slice to query
   * @return storage policy, or null if slice not defined
   */
  public SliceStoragePolicy getStoragePolicy(String sliceName) {
    return sliceDefinitions.get(sliceName);
  }

  /**
   * Returns all defined slices and their storage policies.
   *
   * @return immutable map from slice name to storage policy
   */
  public Map<String, SliceStoragePolicy> getSliceDefinitions() {
    return Map.copyOf(sliceDefinitions);
  }
}
