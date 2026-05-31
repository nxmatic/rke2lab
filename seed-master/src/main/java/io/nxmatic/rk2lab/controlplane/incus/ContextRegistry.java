package io.nxmatic.rk2lab.controlplane.incus;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Type-safe registry for context records in the bootstrap pipeline.
 *
 * <p>Provides a central store for computed/intermediate records that are created during the
 * pipeline and need to be shared across stages without explicit constructor parameter passing.
 *
 * <h2>Design Principles</h2>
 *
 * <ul>
 *   <li><b>Type Safety</b> - Generics ensure no casting needed at lookup sites
 *   <li><b>Explicit Lifecycle</b> - {@code register()} shows when record becomes available
 *   <li><b>Fail-Fast</b> - {@code require()} throws if record not registered (precondition
 *       violation)
 *   <li><b>Optional Pattern</b> - {@code lookup()} returns Optional for truly optional records
 * </ul>
 *
 * <h2>What Goes in Registry</h2>
 *
 * <p><b>DO register:</b>
 *
 * <ul>
 *   <li>Computed records (BootstrapPaths, StagingContext, TargetContext)
 *   <li>Intermediate state (DeploymentMetadata, BuildMetadata)
 *   <li>Context objects with lifecycle (LayerEnvContext)
 * </ul>
 *
 * <p><b>DO NOT register:</b>
 *
 * <ul>
 *   <li>Immutable config (use BootstrapContext record instead)
 *   <li>Services/singletons (use BootstrapContext record instead)
 *   <li>Mutable pipeline state (use ApplyState fields instead)
 *   <li>Provider-specific resources (use ApplyState fields instead)
 * </ul>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * // Register when record is computed
 * BootstrapPaths paths = computePaths(config);
 * registry.register(BootstrapPaths.class, paths);
 *
 * // Require in stages that need it (throws if missing = bug)
 * BootstrapPaths paths = registry.require(BootstrapPaths.class);
 *
 * // Lookup for optional records
 * Optional<DeploymentMetadata> metadata = registry.lookup(DeploymentMetadata.class);
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>This registry is thread-safe using {@link ConcurrentHashMap}. However, the bootstrap pipeline
 * is single-threaded, so this is defensive programming for potential future parallelization.
 *
 * <h2>Design Decision: Why Not Service Locator Anti-Pattern?</h2>
 *
 * <p>Service Locator is an anti-pattern for <b>services</b> because it hides dependencies. This is
 * NOT a service locator because:
 *
 * <ul>
 *   <li>Records are <b>data</b>, not services (no behavior, just computed state)
 *   <li>Lifecycle is <b>explicit</b> - register() calls are visible in the pipeline flow
 *   <li>Scope is <b>narrow</b> - only used within IncusResourceBootstrap pipeline
 *   <li>Alternative is worse - passing 5+ record parameters through every stage constructor
 * </ul>
 *
 * @see BootstrapContext For immutable config and services (passed explicitly)
 * @see ApplyState For mutable pipeline state (fields, not registry)
 */
public final class ContextRegistry {

  private final Map<Class<?>, Object> records = new ConcurrentHashMap<>();

  /**
   * Registers a record in the registry.
   *
   * @param type The record class (used as lookup key)
   * @param record The record instance
   * @param <T> The record type
   * @throws IllegalStateException if a record of this type is already registered
   */
  public <T> void register(Class<T> type, T record) {
    if (record == null) {
      throw new IllegalArgumentException("Cannot register null record for type: " + type.getName());
    }
    Object existing = records.putIfAbsent(type, record);
    if (existing != null) {
      throw new IllegalStateException(
          "Record already registered for type: "
              + type.getName()
              + " (existing="
              + existing
              + ", attempted="
              + record
              + ")");
    }
  }

  /**
   * Updates a record in the registry.
   *
   * <p>Use this when a record needs to be enriched or replaced after initial registration (e.g.,
   * BuildMetadata registered with null image, then updated with actual checksum after provider
   * resources resolve).
   *
   * @param type The record class
   * @param record The new record instance
   * @param <T> The record type
   * @throws IllegalStateException if no record is currently registered for this type
   */
  public <T> void update(Class<T> type, T record) {
    if (record == null) {
      throw new IllegalArgumentException(
          "Cannot update to null record for type: " + type.getName());
    }
    Object existing = records.replace(type, record);
    if (existing == null) {
      throw new IllegalStateException(
          "Cannot update non-existent record: "
              + type.getName()
              + " (use register() for initial registration)");
    }
  }

  /**
   * Requires a record from the registry.
   *
   * <p>Use this for records that MUST be available at the call site (precondition). If the record
   * is not registered, this indicates a bug in the pipeline sequencing.
   *
   * @param type The record class
   * @param <T> The record type
   * @return The registered record instance
   * @throws IllegalStateException if no record is registered for this type
   */
  @SuppressWarnings("unchecked")
  public <T> T require(Class<T> type) {
    Object record = records.get(type);
    if (record == null) {
      throw new IllegalStateException(
          "Required record not found in registry: "
              + type.getName()
              + " (available types: "
              + records.keySet()
              + ")");
    }
    return (T) record;
  }

  /**
   * Looks up a record from the registry.
   *
   * <p>Use this for records that MAY be available (optional). Returns {@link Optional#empty()} if
   * not registered.
   *
   * @param type The record class
   * @param <T> The record type
   * @return Optional containing the record if registered, empty otherwise
   */
  @SuppressWarnings("unchecked")
  public <T> Optional<T> lookup(Class<T> type) {
    return Optional.ofNullable((T) records.get(type));
  }

  /**
   * Checks if a record is registered.
   *
   * @param type The record class
   * @return true if a record is registered for this type
   */
  public boolean contains(Class<?> type) {
    return records.containsKey(type);
  }

  /**
   * Returns the number of registered records.
   *
   * <p>Useful for debugging/logging pipeline state.
   */
  public int size() {
    return records.size();
  }

  @Override
  public String toString() {
    return "ContextRegistry{registeredTypes=" + records.keySet() + "}";
  }
}
