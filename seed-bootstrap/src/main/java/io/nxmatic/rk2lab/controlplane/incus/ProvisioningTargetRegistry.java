package io.nxmatic.rk2lab.controlplane.incus;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry for provisioning targets — independently reconcilable downstream consumers.
 *
 * <p>Components register their targets during materialization; the registry then drives per-target
 * checksum computation. Target changes trigger target-specific reconciliation without full master
 * renewal.
 */
public final class ProvisioningTargetRegistry {

  private final Map<String, TargetReloadPolicy> targetDefinitions = new LinkedHashMap<>();
  private final Map<String, List<Path>> targetRoots = new LinkedHashMap<>();
  private final Map<Path, String> pathOwnership = new LinkedHashMap<>();

  public ProvisioningTargetRegistry() {}

  /**
   * Register a provisioning target.
   *
   * <p>The target must have materialized its resources before registration. The registry extracts
   * the target's name, reload policy, and materialized paths.
   *
   * @param target the target component to register
   * @throws IllegalStateException if a target with the same name is already registered
   * @throws IllegalArgumentException if a path conflicts with an existing target
   */
  public void register(ProvisioningTarget target) {
    if (target == null) {
      throw new IllegalArgumentException("Target must not be null");
    }

    final String targetName = target.name();
    final TargetReloadPolicy policy = target.reloadPolicy();
    final List<Path> paths = target.getMaterializedPaths();

    if (targetName == null || targetName.isBlank()) {
      throw new IllegalArgumentException("Target name must not be null or blank");
    }
    if (policy == null) {
      throw new IllegalArgumentException("Target reload policy must not be null");
    }
    if (targetDefinitions.containsKey(targetName)) {
      throw new IllegalStateException("Target already registered: " + targetName);
    }

    targetDefinitions.put(targetName, policy);

    if (paths != null && !paths.isEmpty()) {
      // Single-ownership for *exact* paths. Nested ownership is allowed: a parent target may
      // register a directory whose subtree is partially owned by other targets (e.g.
      // ClusterTarget owns manifestsRoot, RuntimeConfigTarget owns
      // manifestsRoot/runtime/{rke2-config,env-config}). The checksum walker filters descendants
      // owned elsewhere when it walks a parent path so each file is hashed by exactly one target.
      for (Path root : paths) {
        final String existingOwner = pathOwnership.get(root);
        if (existingOwner != null && !existingOwner.equals(targetName)) {
          throw new IllegalArgumentException(
              "Path already registered in target '"
                  + existingOwner
                  + "': "
                  + root
                  + " (attempted to register in '"
                  + targetName
                  + "')");
        }
        pathOwnership.put(root, targetName);
      }

      targetRoots.put(targetName, new ArrayList<>(paths));
    }
  }

  /**
   * Returns paths owned by other targets that are descendants of {@code parent}. Used by the
   * checksum walker to skip subtrees claimed by a more specific target so each file is hashed
   * exactly once.
   *
   * @param parent root path being walked
   * @param ownerName target that owns {@code parent}; descendants owned by this same target are not
   *     returned
   */
  public java.util.Set<Path> nestedForeignDescendants(Path parent, String ownerName) {
    final java.util.LinkedHashSet<Path> descendants = new java.util.LinkedHashSet<>();
    for (Map.Entry<Path, String> entry : pathOwnership.entrySet()) {
      final Path candidate = entry.getKey();
      if (candidate.equals(parent)) {
        continue;
      }
      if (entry.getValue().equals(ownerName)) {
        continue;
      }
      if (candidate.startsWith(parent)) {
        descendants.add(candidate);
      }
    }
    return descendants;
  }

  /**
   * Returns an immutable view of registered targets and their roots.
   *
   * @return map from target name to list of filesystem roots
   */
  public Map<String, List<Path>> getTargetRoots() {
    final LinkedHashMap<String, List<Path>> copy = new LinkedHashMap<>();
    for (Map.Entry<String, List<Path>> entry : targetRoots.entrySet()) {
      copy.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    return Map.copyOf(copy);
  }

  /**
   * Returns true if the named target has been registered.
   *
   * @param targetName target to check
   * @return true if at least one root is registered for this target
   */
  public boolean hasTarget(String targetName) {
    return targetRoots.containsKey(targetName);
  }

  /**
   * Returns the reload policy for the named target.
   *
   * @param targetName target to query
   * @return reload policy, or null if target not defined
   */
  public TargetReloadPolicy getReloadPolicy(String targetName) {
    return targetDefinitions.get(targetName);
  }

  /**
   * Returns all defined targets and their reload policies.
   *
   * @return immutable map from target name to reload policy
   */
  public Map<String, TargetReloadPolicy> getTargetDefinitions() {
    return Map.copyOf(targetDefinitions);
  }
}
