package io.nxmatic.rke2lab.controlplane.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.report.model.ReportModel;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;

/**
 * The host's inbound seam: reads what the driver seeded into the JUnit session store and wires it
 * into the run — BEFORE {@code JGivenExtension.postProcessTestInstance}, because this extension is
 * declared first on the scenario class. Store lookups walk the parent chain up to the session-level
 * store the driver seeded (cross-thread safe: a ConcurrentMap-backed NamespacedHierarchicalStore).
 *
 * <p>Two things ride the store inbound, with different sinks:
 *
 * <ul>
 *   <li>{@link #HOST_FACTS} → the scenario instance's {@code @ProvidedScenarioState} (via {@link
 *       HostFactsAware}).
 *   <li>{@link #RUN_MODEL}, the driver's own {@code ReportModel} → jGiven's OWN store slot. We do
 *       NOT call {@code setModel} on the scenario: jGiven's {@code postProcessTestInstance},
 *       running after us, installs the model from its store and would overwrite ours. Instead we
 *       overwrite jGiven's store entry (namespace {@code com.tngtech.jgiven}, key {@code
 *       report-model}) so jGiven adopts the driver's model as its own. jGiven writes the run into
 *       it; the driver, holding the same reference, renders the runbook from it — one model, one
 *       owner, no harvest-back.
 * </ul>
 */
public final class HostSeeder implements TestInstancePostProcessor {

  public static final Namespace NS = Namespace.create(HostSeeder.class);
  public static final String HOST_FACTS = "host-facts";

  /** Inbound key under {@link #NS}: the driver's own {@code ReportModel} (see class javadoc). */
  public static final String RUN_MODEL = "run-model";

  /**
   * jGiven's own report-model store coordinate — derived, not hardcoded: the namespace is jGiven's
   * base package (via {@link Stage}), the key its store slot. We overwrite this so jGiven adopts
   * the driver's model as its own.
   */
  private static final Namespace JGIVEN_NS = Namespace.create(Stage.class.getPackageName());

  private static final String JGIVEN_REPORT_MODEL = "report-model";

  @Override
  public void postProcessTestInstance(Object testInstance, ExtensionContext context) {
    final HostFacts facts = context.getStore(NS).get(HOST_FACTS, HostFacts.class);
    if (facts != null && testInstance instanceof HostFactsAware aware) {
      aware.acceptHostFacts(facts);
    }
    final ReportModel runbook = context.getStore(NS).get(RUN_MODEL, ReportModel.class);
    if (runbook != null) {
      context.getStore(JGIVEN_NS).put(JGIVEN_REPORT_MODEL, runbook);
    }
  }

  /** Implemented by the scenario so the seeder sets its state without reflection. */
  public interface HostFactsAware {
    void acceptHostFacts(HostFacts facts);
  }
}
