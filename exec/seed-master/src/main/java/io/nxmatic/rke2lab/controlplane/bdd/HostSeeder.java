package io.nxmatic.rke2lab.controlplane.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.impl.Config;
import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.OsgiConnection;
import java.util.Optional;
import org.junit.jupiter.api.extension.AfterAllCallback;
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
public final class HostSeeder implements TestInstancePostProcessor, AfterAllCallback {

  public static final Namespace NS = Namespace.create(HostSeeder.class);
  public static final String HOST_FACTS = "host-facts";

  /**
   * jGiven's report-enabled system property. jGiven's {@code afterAll} writes an on-disk report
   * (via {@code CommonReportHelper}) gated on this; a scenario played by our launcher (not
   * surefire) has no configured report dir, so it would dump a stray {@code ./jgiven-reports}.
   * jGiven exposes this ONLY as a process-global property (its {@code Config} is a closed singleton
   * — we cannot contribute a scoped config), so we override it and RESTORE it in {@link #afterAll}
   * to leave a clean context for any other jGiven run sharing the JVM.
   */
  private static final String REPORT_ENABLED_PROPERTY = "jgiven.report.enabled";

  /** Store key: the property's value before we overrode it (an {@link java.util.Optional}). */
  private static final String REPORT_ENABLED_PRIOR = "report-enabled-prior";

  /** Inbound key under {@link #NS}: the driver's own {@code ReportModel} (see class javadoc). */
  public static final String RUN_MODEL = "run-model";

  /** Inbound key under {@link #NS}: the live OSGi connection the phases attach to. */
  public static final String CONNECTION = "connection";

  /** Inbound key under {@link #NS}: the phase collaborators (live in prod, fakes in tests). */
  public static final String PROBES = "probes";

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
    final OsgiConnection connection = context.getStore(NS).get(CONNECTION, OsgiConnection.class);
    if (connection != null && testInstance instanceof ConnectionAware aware) {
      aware.acceptConnection(connection);
    }
    final SeedProbes probes = context.getStore(NS).get(PROBES, SeedProbes.class);
    if (probes != null && testInstance instanceof ProbesAware aware) {
      aware.acceptProbes(probes);
    }
    final ReportModel runbook = context.getStore(NS).get(RUN_MODEL, ReportModel.class);
    if (runbook != null) {
      // jGiven created its own model in beforeAll and named it (test class + display name). We
      // replace it with the driver's, but carry that identity over — else the model is nameless
      // (a "null.json" report, a "Test Class: null" rendering).
      final ReportModel named =
          context.getStore(JGIVEN_NS).get(JGIVEN_REPORT_MODEL, ReportModel.class);
      if (named != null) {
        runbook.setName(named.getName());
        runbook.setClassName(named.getClassName());
        runbook.setDescription(named.getDescription());
      }
      context.getStore(JGIVEN_NS).put(JGIVEN_REPORT_MODEL, runbook);
    }
    // Disable jGiven's on-disk report for this launcher run (see REPORT_ENABLED_PROPERTY), saving
    // the prior value so afterAll can restore it — no leaked global state.
    context
        .getStore(NS)
        .put(
            REPORT_ENABLED_PRIOR, Optional.ofNullable(System.getProperty(REPORT_ENABLED_PROPERTY)));
    Config.config().setReportEnabled(false);
  }

  /**
   * Restore the report-enabled property to its prior value. Runs AFTER jGiven's {@code afterAll}
   * (which consulted the flag to write — or skip — its report): this extension is declared first,
   * so in the reverse afterAll order it finishes last. Leaves the JVM's jGiven config as we found
   * it.
   */
  @Override
  public void afterAll(ExtensionContext context) {
    @SuppressWarnings("unchecked")
    final Optional<String> prior = context.getStore(NS).get(REPORT_ENABLED_PRIOR, Optional.class);
    if (prior == null || prior.isEmpty()) {
      System.clearProperty(REPORT_ENABLED_PROPERTY);
    } else {
      System.setProperty(REPORT_ENABLED_PROPERTY, prior.get());
    }
  }

  /** Implemented by the scenario so the seeder sets its state without reflection. */
  public interface HostFactsAware {
    void acceptHostFacts(HostFacts facts);
  }

  /**
   * Implemented by the scenario so the seeder installs the live OSGi connection — the world the
   * phases attach to (without owning its lifecycle) to reach model services.
   */
  public interface ConnectionAware {
    void acceptConnection(OsgiConnection connection);
  }

  /**
   * Implemented by the scenario so the seeder installs the phase collaborators — live in prod,
   * fakes in tests, the instance-passing seam that lets the scenario play offline.
   */
  public interface ProbesAware {
    void acceptProbes(SeedProbes probes);
  }
}
