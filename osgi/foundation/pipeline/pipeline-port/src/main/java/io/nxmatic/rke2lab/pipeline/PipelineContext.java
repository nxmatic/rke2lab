package io.nxmatic.rke2lab.pipeline;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Type-safe registry for the AMBIENT records a fluent pipeline threads across its topics — the
 * transverse data known before the first topic (modes, orchestrators, resolved services, shared
 * charts), keyed by its class so no cast is needed at the lookup site.
 *
 * <p>This is the single mechanism every pipeline uses for ambient state, generalized from the
 * per-pipeline registries that were each reinvented. It carries AMBIENT only — never the flux
 * between topics. Flux (an upstream topic's output consumed by a downstream one) is carried by the
 * typed input record {@code I} a transition builds explicitly, so a missing flux value is a compile
 * error, not a runtime one.
 *
 * <h2>The determinism discipline</h2>
 *
 * <p>A context is read AND written ONLY while a transition builds a topic's input — never inside a
 * topic. A {@link Topic} does not receive the context ({@code run(I)}, not {@code run(I,
 * context)}), so it cannot read a record at an arbitrary instant nor write one back. That
 * confinement is what makes each topic's input deterministic: at the moment {@code I} is assembled
 * it is frozen, and the topic has no handle to reopen the context.
 *
 * <h2>No {@code update}</h2>
 *
 * <p>There is deliberately no {@code update()}: an ambient record is registered once, before the
 * first topic, and never mutated mid-run. A value that changes as the run proceeds is an OUTPUT
 * (flux), not ambient — it belongs in the state accumulator, produced by a topic and folded at a
 * transition, not enriched in place here.
 *
 * <h2>Fail-fast</h2>
 *
 * <p>{@link #require(Class)} throws if a record is absent — a mis-wiring surfaced at the first run,
 * never a silent default. Because ambient records are registered before the first topic (nothing
 * order-conditional belongs here), a miss means the pipeline was assembled wrong, not that a value
 * is "not yet produced". {@link #lookup(Class)} returns {@link Optional} for the genuinely
 * optional.
 *
 * <p>Backed by a {@link ConcurrentHashMap} for defensive thread-safety; pipelines are
 * single-threaded today.
 *
 * @see Topic
 */
public final class PipelineContext {

  private final Map<Class<?>, Object> records = new ConcurrentHashMap<>();

  /**
   * Registers an ambient record under its class.
   *
   * @throws IllegalStateException if a record of this type is already registered
   */
  public <T> void register(Class<T> type, T record) {
    final Object existing = records.putIfAbsent(type, record);
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
   * Requires an ambient record that MUST be present (a precondition). Absence indicates the
   * pipeline was assembled wrong.
   *
   * @throws IllegalStateException if no record is registered for this type
   */
  public <T> T require(Class<T> type) {
    final Object record = records.get(type);
    if (record == null) {
      throw new IllegalStateException(
          "Required record not found in context: "
              + type.getName()
              + " (available types: "
              + records.keySet()
              + ")");
    }
    return type.cast(record);
  }

  /** Looks up a genuinely-optional ambient record; empty when absent. */
  public <T> Optional<T> lookup(Class<T> type) {
    final Object record = records.get(type);
    return record == null ? Optional.empty() : Optional.of(type.cast(record));
  }

  /** Whether a record is registered for this type. */
  public boolean contains(Class<?> type) {
    return records.containsKey(type);
  }

  @Override
  public String toString() {
    return "PipelineContext{registeredTypes=" + records.keySet() + "}";
  }
}
