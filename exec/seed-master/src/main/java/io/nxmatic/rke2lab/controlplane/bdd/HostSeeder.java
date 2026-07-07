package io.nxmatic.rke2lab.controlplane.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Config;
import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.OsgiConnection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
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
 *   <li>the phase context ({@link #HOST_FACTS}, {@link #CONNECTION}, {@link #PROBES}, the two probe
 *       overrides, the outputs sink) → collected into ONE {@link StageContext} carrier and pushed
 *       into the run's value-DAG with {@code executor.readScenarioState(carrier)}. The stages
 *       resolve their {@code @ExpectedScenarioState} from that DAG — the single injection channel,
 *       no {@code *Aware}/{@code accept*} on the scenario.
 *   <li>the run-model holder ({@link #RUN_MODEL}) → we publish jGiven's OWN {@code ReportModel}
 *       (created and named in jGiven's {@code beforeAll}, never replaced) into the driver's holder.
 *       jGiven writes the run into its model; the driver, holding the same reference, renders the
 *       runbook — one model (jGiven's), no plant, no identity copy, no harvest-back.
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

  /**
   * Inbound key under {@link #NS}: the driver's run-model holder, an {@link
   * java.util.concurrent.atomic.AtomicReference} of {@code ReportModel}. HostSeeder publishes
   * jGiven's OWN (already-named) model into it; the driver reads the same reference after the run
   * and renders. Inject-the-holder, twin of {@link #OUTPUTS_SINK} — see class javadoc.
   */
  public static final String RUN_MODEL = "run-model";

  /** Inbound key under {@link #NS}: the live OSGi connection the phases attach to. */
  public static final String CONNECTION = "connection";

  /** Inbound key under {@link #NS}: the phase collaborators (live in prod, fakes in tests). */
  public static final String PROBES = "probes";

  /**
   * Inbound key under {@link #NS}: an OPTIONAL systemd-adapter endpoint probe. Seeded only by a
   * test (a reachable/failing fake) so the systemd phase plays offline; absent in the live boot,
   * where the stage resolves the live probe from the registry itself. A channel of its own, NOT
   * part of {@link SeedProbes}: the pure phases' probes are injected always, this one only
   * overrides the registry-resolved live path in tests (the two probe axes — see the systemd
   * stage).
   */
  public static final String SYSTEMD_PROBE = "systemd-probe";

  /**
   * Inbound key under {@link #NS}: an OPTIONAL cluster-readiness phase probe. Seeded only by a test
   * (a per-phase fake) so the cluster-readiness phase plays offline; absent in the live boot, where
   * the stage resolves the live probe from the registry (the kubectl contact). The cluster twin of
   * {@link #SYSTEMD_PROBE}.
   */
  public static final String CLUSTER_PROBE = "cluster-probe";

  /**
   * Inbound key under {@link #NS}: the driver's outputs sink — an {@link
   * java.util.concurrent.atomic.AtomicReference} the driver holds and the terminal {@link
   * OutputsStage} publishes the collected outputs into. The inject-the-holder idiom, twin of {@link
   * #RUN_MODEL}: one holder, seeded by the driver, filled by the run, read by the driver — no
   * static, no harvest-back. Optional by nature: a focused stage unit test that ignores outputs
   * omits it, so the carrier's field stays {@link Optional#empty()}.
   */
  public static final String OUTPUTS_SINK = "outputs-sink";

  /**
   * jGiven's own report-model store coordinate — derived, not hardcoded: the namespace is jGiven's
   * base package (via {@link Stage}), the key its store slot. We overwrite this so jGiven adopts
   * the driver's model as its own.
   */
  private static final Namespace JGIVEN_NS = Namespace.create(Stage.class.getPackageName());

  private static final String JGIVEN_REPORT_MODEL = "report-model";

  @Override
  public void postProcessTestInstance(Object testInstance, ExtensionContext context) {
    // Only a ScenarioTestBase carries an executor + a value-DAG to seed into; anything else is not
    // ours to touch.
    if (!(testInstance instanceof ScenarioTestBase<?, ?, ?> scenarioBase)) {
      return;
    }
    // The host bag is REQUIRED: the driver always seeds it before launching, so its absence is a
    // broken contract, not a case to skip silently.
    final HostFacts facts =
        Objects.requireNonNull(
            context.getStore(NS).get(HOST_FACTS, HostFacts.class),
            "HOST_FACTS not seeded (the driver must seed the host bag before launching)");

    // Preview (the LiveGate closed): swap in the E9 executor so the runbook renders the full plan
    // as PENDING without deferring the tree. This runs BEFORE jGiven's postProcessTestInstance,
    // whose setModel wires the model-builder listener onto whichever executor the scenario holds —
    // so our decorate is in place before the listener is attached (verified: ScenarioBase.setModel
    // → executor.setListener). In a live run the default executor stays, so the decorate is inert.
    if (!facts.liveGate().isOpen()) {
      scenarioBase.getScenario().setExecutor(new PendingMarkingScenarioExecutor(true));
    }

    // Fill ONE carrier from the store and push it into the run's value-DAG. This is the single way
    // a stage receives context: it resolves its @ExpectedScenarioState from the same map jGiven
    // uses for phase-to-phase flow. No parallel *Aware/accept* channel, no pass-through fields on
    // the scenario. readScenarioState only ADDS to the executor's (final, never-cleared) injector
    // map, and runs before jGiven's own init (lazy, on first when()), so the seed survives the run.
    final StageContext carrier = new StageContext();
    carrier.hostFacts = facts;
    // The connection is REQUIRED too: every cluster-seed phase reaches the OSGi world through it,
    // so
    // the driver (holding a booted framework) always seeds it — its absence is a broken contract.
    carrier.connection =
        Objects.requireNonNull(
            context.getStore(NS).get(CONNECTION, OsgiConnection.class),
            "CONNECTION not seeded (the driver must seed the OSGi connection before launching)");
    final SeedProbes probes = context.getStore(NS).get(PROBES, SeedProbes.class);
    if (probes != null) {
      carrier.preflightProbe = probes.preflight();
      carrier.bboxProbe = probes.bbox();
      carrier.incusProbe = probes.incus();
    }
    carrier.injectedProbe =
        Optional.ofNullable(context.getStore(NS).get(SYSTEMD_PROBE, SystemdAdapterProbe.class));
    carrier.clusterProbe =
        Optional.ofNullable(context.getStore(NS).get(CLUSTER_PROBE, ClusterReadinessProbe.class));
    @SuppressWarnings("unchecked")
    final AtomicReference<Map<String, Object>> outputsSink =
        context.getStore(NS).get(OUTPUTS_SINK, AtomicReference.class);
    carrier.outputsSink = Optional.ofNullable(outputsSink);
    scenarioBase.getScenario().getExecutor().readScenarioState(carrier);

    // Hand the driver jGiven's OWN model — inject-the-holder, the twin of the outputs sink. jGiven
    // created and NAMED its model in beforeAll (test class + display name) and never replaces that
    // instance (it re-reads the same store slot in afterAll to finalize it — verified). So rather
    // than plant our own model and copy jGiven's identity onto it, we just publish jGiven's model
    // into the driver's holder: jGiven writes the run into it, the driver reads the same reference
    // and renders. One model, jGiven's, no plant, no identity copy.
    @SuppressWarnings("unchecked")
    final AtomicReference<ReportModel> runModelHolder =
        context.getStore(NS).get(RUN_MODEL, AtomicReference.class);
    if (runModelHolder != null) {
      // jGiven's beforeAll (which ran before us) always put its model in this slot — its absence
      // would be a broken jGiven contract, not a case to skip.
      runModelHolder.set(
          Objects.requireNonNull(
              context.getStore(JGIVEN_NS).get(JGIVEN_REPORT_MODEL, ReportModel.class),
              "jGiven's ReportModel absent from its store (beforeAll did not run)"));
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
}
