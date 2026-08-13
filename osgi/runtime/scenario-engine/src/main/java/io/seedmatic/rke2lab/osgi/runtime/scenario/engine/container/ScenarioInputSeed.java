package io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container;

import java.util.Objects;
import java.util.function.Consumer;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;
import org.junit.platform.engine.support.store.Namespace;
import org.junit.platform.engine.support.store.NamespacedHierarchicalStore;

/**
 * The INBOUND channel carrying a scenario's activation input across the launcher boundary — the
 * in-container twin of {@code seed.bdd.SessionSeed} (which lives in an un-exported foundation
 * package only the host root reaches). It replaces the static {@code INPUT} {@code AtomicReference}
 * + {@code seedInput(...)} setter the input-bearing scions used: the front-door seeds the input via
 * {@link #into}, and this post-processor reads it back and hands it to the scenario's {@link
 * InputReceiver} before the GIVEN runs.
 *
 * <p>The value is a LIVE object (no codec): the hop is IN-REALM — the front-door and the scenario
 * share the bundle loader (the {@code INPUT} static it replaces was a "same-loader static"), so
 * nothing crosses the dual-realm membrane. Both ends address the store by the SAME {@link
 * Namespace} parts (key-derived), so the Jupiter-side {@code getStore} lookup walks the parent
 * chain up to the launcher session store the front-door seeded — the way every inbound seed crosses
 * the two store views.
 *
 * @param <T> the activation input's type — concrete in each domain (a bundle type), never named
 *     here
 */
public final class ScenarioInputSeed<T> implements TestInstancePostProcessor {

  private final Class<T> type;
  private final String key;

  /**
   * A channel for {@code type}, keyed by {@code key}. The SAME key + type on both ends (the
   * front-door's {@link #into} and this post-processor) is what lets the store lookup find the
   * seeded value.
   */
  public ScenarioInputSeed(Class<T> type, String key) {
    this.type = type;
    this.key = key;
  }

  private String[] nsParts() {
    return new String[] {ScenarioInputSeed.class.getName(), key};
  }

  /**
   * The front-door's seeding consumer, handed to {@code JUnitLauncherCore.run(…,
   * seedSessionStore)}: put {@code value} into the launcher session store under this channel's
   * namespace + key. This post-processor reads exactly it back.
   */
  public Consumer<NamespacedHierarchicalStore<Namespace>> into(T value) {
    final Namespace ns = Namespace.create((Object[]) nsParts());
    return store -> store.put(ns, key, value);
  }

  /**
   * Read the seeded input from the session store (via the parent chain) and hand it to the scenario
   * if it is an {@link InputReceiver} of the right type. Runs as a Jupiter post-processor, before
   * jGiven's own — so the input is set before the GIVEN. A scenario that receives no input is left
   * untouched (opt-in by implementing {@link InputReceiver}).
   */
  @Override
  @SuppressWarnings("unchecked")
  public void postProcessTestInstance(Object testInstance, ExtensionContext context) {
    if (!(testInstance instanceof InputReceiver<?> receiver)) {
      return;
    }
    final ExtensionContext.Namespace ns = ExtensionContext.Namespace.create((Object[]) nsParts());
    final T value =
        Objects.requireNonNull(
            context.getStore(ns).get(key, type),
            () ->
                "no '"
                    + key
                    + "' ("
                    + type.getSimpleName()
                    + ") seeded into the session store — the front-door must seed it before launching");
    ((InputReceiver<T>) receiver).receiveInput(value);
  }
}
