package io.nxmatic.rke2lab.controlplane.bdd;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;

/**
 * Reads {@link HostFacts} from the JUnit session store (seeded by the host driver before the run)
 * and pushes them onto the scenario instance's {@code @ProvidedScenarioState} field — BEFORE {@code
 * JGivenExtension.postProcessTestInstance} siphons scenario-state, because this extension is
 * declared first on the scenario class. The store lookup walks the parent chain up to the
 * session-level store the driver seeded (cross-thread safe: a ConcurrentMap-backed
 * NamespacedHierarchicalStore).
 */
public final class HostFactsSeeder implements TestInstancePostProcessor {

  public static final Namespace NS = Namespace.create(HostFactsSeeder.class);
  public static final String HOST_FACTS = "host-facts";

  @Override
  public void postProcessTestInstance(Object testInstance, ExtensionContext context) {
    final HostFacts facts = context.getStore(NS).get(HOST_FACTS, HostFacts.class);
    if (facts != null && testInstance instanceof HostFactsAware aware) {
      aware.acceptHostFacts(facts);
    }
  }

  /** Implemented by the scenario so the seeder sets its state without reflection. */
  public interface HostFactsAware {
    void acceptHostFacts(HostFacts facts);
  }
}
