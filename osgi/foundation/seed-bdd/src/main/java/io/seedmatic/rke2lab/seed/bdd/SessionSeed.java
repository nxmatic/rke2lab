package io.seedmatic.rke2lab.seed.bdd;

import java.util.Objects;
import java.util.function.Consumer;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;
import org.junit.platform.engine.support.store.Namespace;
import org.junit.platform.engine.support.store.NamespacedHierarchicalStore;

/**
 * The generic bootstrap channel: carry ONE driver-captured fact into the root scenario through the
 * launcher's session store. The driver ({@code Main}, inside {@code Pulumi.run}) captures the fact
 * only it can know (the {@code RunMode}) and seeds it via {@link #into}; this post-processor reads
 * it back and hands it to the scenario's {@link SeedReceiver} before the GIVEN runs, so the GIVEN
 * bootstraps from it.
 *
 * <p>Why the store works across the two realms: a Jupiter extension's {@code
 * ExtensionContext.getStore} walks the parent chain up to the session-level store the launcher
 * seeded — same {@link Namespace} parts on both sides — so no bridge code is written (see
 * docs/architecture/osgi/seed-bdd-module-spec.adoc § the amorce). The seed value's type stays the
 * exec's ({@code RunMode} is host-only); this class is generic over it and names none.
 *
 * @param <T> the seeded fact's type
 */
public final class SessionSeed<T> implements TestInstancePostProcessor {

  private final Class<T> type;
  private final String key;

  /**
   * A channel for {@code type}, keyed by {@code key}. The SAME key + type on both ends (the
   * driver's {@link #into} and this post-processor) is what lets the store lookup find the seeded
   * value.
   */
  public SessionSeed(Class<T> type, String key) {
    this.type = type;
    this.key = key;
  }

  /**
   * The shared namespace both ends address the store through — derived from the key, one source.
   */
  private Namespace namespace() {
    return Namespace.create(SessionSeed.class.getName(), key);
  }

  /**
   * The driver's seeding consumer, handed to {@code JUnitLauncherCore.run(…, seedSessionStore)}:
   * put {@code value} into the launcher session store under this channel's namespace + key. The
   * post-processor reads exactly this back.
   */
  public Consumer<NamespacedHierarchicalStore<Namespace>> into(T value) {
    final Namespace ns = namespace();
    return store -> store.put(ns, key, value);
  }

  /**
   * Read the seeded value from the session store (via the parent chain) and hand it to the scenario
   * if it is a {@link SeedReceiver} of the right type. Runs as a Jupiter post-processor, before
   * jGiven's own — so the value is set before the GIVEN. A scenario that does not receive this seed
   * is left untouched (the channel is opt-in by implementing {@link SeedReceiver}).
   */
  @Override
  @SuppressWarnings("unchecked")
  public void postProcessTestInstance(Object testInstance, ExtensionContext context) {
    if (!(testInstance instanceof SeedReceiver<?> receiver)) {
      return;
    }
    final T value =
        Objects.requireNonNull(
            context.getStore(toJupiterNamespace()).get(key, type),
            () ->
                "no '"
                    + key
                    + "' ("
                    + type.getSimpleName()
                    + ") seeded into the session store — the driver must seed it before launching");
    ((SeedReceiver<T>) receiver).receiveSeed(value);
  }

  /**
   * The Jupiter-side view of the same namespace — its parts must equal the launcher-side {@link
   * Namespace}'s so the store lookup crosses the two realms.
   */
  private ExtensionContext.Namespace toJupiterNamespace() {
    return ExtensionContext.Namespace.create(SessionSeed.class.getName(), key);
  }
}
