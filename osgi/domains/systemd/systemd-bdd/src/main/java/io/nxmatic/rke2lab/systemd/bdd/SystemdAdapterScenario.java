package io.nxmatic.rke2lab.systemd.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import com.tngtech.jgiven.junit5.JGivenExtension;
import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.doctor.contract.Checkpoint;
import io.nxmatic.rke2lab.doctor.contract.ConsultingService;
import io.nxmatic.rke2lab.doctor.contract.DoctorCoordinate;
import io.nxmatic.rke2lab.doctor.contract.ObservationWire;
import io.nxmatic.rke2lab.doctor.contract.ReadinessCheckpoint;
import io.nxmatic.rke2lab.doctor.contract.SymptomKind;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioRegistry;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.nxmatic.rke2lab.systemd.contract.SystemdProbeRequest;
import io.nxmatic.rke2lab.systemd.contract.SystemdRuntimeProbe;
import io.nxmatic.rke2lab.systemd.contract.SystemdStatusSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The systemd-adapter readiness checkpoint, a production jGiven scenario told in the SYSTEMD
 * DOMAIN's own vocabulary — {@link SystemdRuntimeProbe} opened once over its dbus-on-TCP endpoint,
 * the returned {@link SystemdStatusSnapshot} asserted facet by facet (mandatory target healthy, no
 * failed units, runtime precheck ready), no host/Pulumi type. Played IN-CONTAINER by the engine so
 * the runbook shows a real node of the OSGi world; it lives in {@code systemd-bdd} (only ports, no
 * sealed internal), not a {@code -test} fragment (it is live seeding logic).
 *
 * <p>The systemd twin of {@code ClusterReadinessScenario}: identical shape, one difference of
 * nature — systemd has no phase chain, so the single probe's snapshot is read across a small chain
 * of readable assertions rather than a chain of contact calls. It resolves its collaborator — the
 * {@link SystemdRuntimeProbe} — from its OWN bundle's registry ({@link ScenarioRegistry}); the
 * scenario is identical live and in test, only who published the probe differs (the live {@code
 * DbusSystemdProbe}, or a mock a test seeds into the registry before playing). A not-ready facet
 * throws, jGiven marks it FAILED and skips the downstream chained assertions, so the runbook shows
 * exactly which systemd fact broke.
 *
 * <p>The endpoint the probe opens is described by a fixed {@link SystemdProbeRequest} marker (as
 * {@code ClusterReadinessScenario} uses a fixed kubeconfig path): the offline mock ignores it, and
 * the live endpoint-config plumbing is the same deferral the cluster twin makes.
 */
@ExtendWith(JGivenExtension.class)
public class SystemdAdapterScenario
    extends ScenarioTestBase<
        SystemdAdapterScenario.Given, SystemdAdapterScenario.When, SystemdAdapterScenario.Then> {

  // The played model, harvested by the front-door into the envelope. An initialized holder (never
  // null) so the null-hygiene gate stays green; the run fills it.
  private static final AtomicReference<ReportModel> LAST_RUNBOOK = new AtomicReference<>();

  // The doctor consultations the run raised on a failing facet, harvested by the front-door into
  // the
  // envelope alongside the runbook. Empty when every facet passed (a healthy run consults no one).
  private static final AtomicReference<List<SeedEnvelope>> LAST_CONSULTATIONS =
      new AtomicReference<>(List.of());

  static ReportModel lastRunbook() {
    return Objects.requireNonNull(
        LAST_RUNBOOK.get(), "the scenario has not played yet — no runbook to harvest");
  }

  static List<SeedEnvelope> lastConsultations() {
    return LAST_CONSULTATIONS.get();
  }

  private final Scenario<Given, When, Then> scenario = createScenario();

  @Override
  public Scenario<Given, When, Then> getScenario() {
    return scenario;
  }

  @Test
  void the_systemd_adapter_becomes_reachable() {
    final SystemdRuntimeProbe probe = resolveProbe();
    final List<ObservationWire> observations = new ArrayList<>();
    given().the_seed_node("seed").and().probed_through(probe, observations);
    when()
        .the_systemd_endpoint_is_probed()
        .and()
        .the_mandatory_target_is_healthy()
        .and()
        .no_units_have_failed();
    then().the_systemd_adapter_is_reachable();
    LAST_RUNBOOK.set(getScenario().getModel());
    LAST_CONSULTATIONS.set(consultOnFailure(observations));
  }

  /**
   * The domain consults the doctor ITSELF on a failing facet (fork B: the checkpoint owns its
   * consult, not the host). A facet reported not-ready recorded its {@link ObservationWire}
   * carrying a typed {@link SymptomKind}; if any is non-ok, resolve the doctor's {@link
   * ConsultingService} from THIS bundle's registry, build the {@code readiness-checkpoint}
   * SeedEnvelope around the observations, and consult. The returned {@code consultation} {@code
   * SeedEnvelope}s ride the envelope back to the host, which records them. A healthy run raised no
   * symptom, so it consults no one and returns an empty list.
   */
  private List<SeedEnvelope> consultOnFailure(List<ObservationWire> observations) {
    final boolean anySymptom = observations.stream().anyMatch(o -> o.symptom().isPresent());
    if (!anySymptom) {
      return List.of();
    }
    return resolveDoctor()
        .map(doctor -> List.of(doctor.consult(consultCheckpoint(observations))))
        .orElseGet(List::of);
  }

  /**
   * The {@code readiness-checkpoint} SeedEnvelope the domain hands the doctor — its observations,
   * named by the systemd-adapter checkpoint the host joins the runbook on.
   */
  private static SeedEnvelope consultCheckpoint(List<ObservationWire> observations) {
    final ReadinessCheckpoint checkpoint =
        new ReadinessCheckpoint(
            Checkpoint.SYSTEMD_ADAPTER.slug(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            List.copyOf(observations));
    final SeedCodec codec = new SeedCodec();
    return SeedEnvelope.of(DoctorCoordinate.READINESS_CHECKPOINT, codec.encode(checkpoint));
  }

  /**
   * The endpoint the probe opens. A fixed marker in the offline scenario (the mock ignores it); the
   * live endpoint-config plumbing is deferred, exactly as the cluster twin defers its kubeconfig.
   */
  private static SystemdProbeRequest endpoint() {
    return new SystemdProbeRequest("localhost", 0, "seed", "unknown");
  }

  /**
   * Resolve the systemd runtime probe from THIS bundle's registry (via {@link ScenarioRegistry}). A
   * test seeds a mock under the same interface before playing; live, SCR has published {@code
   * DbusSystemdProbe}.
   */
  private SystemdRuntimeProbe resolveProbe() {
    return ScenarioRegistry.of(this)
        .require(
            SystemdRuntimeProbe.class,
            "no SystemdRuntimeProbe in the registry (live edge or test mock must publish one)");
  }

  /**
   * Resolve the doctor's {@link ConsultingService} from THIS bundle's registry, or {@link
   * Optional#empty()} if none is published — a real runtime condition (a world booted without the
   * doctor), so a failing facet without a doctor degrades to no consultation rather than a crash.
   */
  private Optional<ConsultingService> resolveDoctor() {
    return ScenarioRegistry.of(this).optional(ConsultingService.class);
  }

  /** Given: the seed node to reach, the probe, and the shared observation buffer the When fills. */
  public static class Given extends Stage<Given> {

    @ProvidedScenarioState SystemdRuntimeProbe probe;

    /** The snapshot the probe returned, read across the When's facet assertions. */
    @ProvidedScenarioState SystemdStatusSnapshot snapshot;

    /**
     * The per-facet observations the When records — the material the domain's own consult reads.
     */
    @ProvidedScenarioState List<ObservationWire> observations;

    public Given the_seed_node(String name) {
      return self();
    }

    @Hidden
    public Given probed_through(SystemdRuntimeProbe probe, List<ObservationWire> observations) {
      this.probe = probe;
      this.observations = observations;
      return self();
    }
  }

  /**
   * When: the single probe is opened once, then its {@link SystemdStatusSnapshot} is read across a
   * small chain of readable facet assertions. Each facet records its {@link ObservationWire} (ok,
   * or failed with a typed {@link SymptomKind}) into the shared buffer, then a not-ready facet
   * throws — jGiven marks its step FAILED and skips the downstream chained steps. Fail-fast is the
   * chain's own semantics; the recorded observations are what the domain's own doctor consult
   * reads.
   */
  public static class When extends Stage<When> {

    @ExpectedScenarioState SystemdRuntimeProbe probe;
    @ExpectedScenarioState List<ObservationWire> observations;

    @ProvidedScenarioState SystemdStatusSnapshot snapshot;

    public When the_systemd_endpoint_is_probed() {
      try {
        this.snapshot = probe.probe(endpoint());
      } catch (RuntimeException unreachable) {
        record("systemd endpoint", false, SymptomKind.CONNECTION_REFUSED);
        throw new AssertionError("systemd endpoint: " + unreachable.getMessage(), unreachable);
      }
      return check(
          "systemd endpoint", snapshot.runtimePrecheckReady(), SymptomKind.CONNECTION_REFUSED);
    }

    public When the_mandatory_target_is_healthy() {
      return check(
          "mandatory target", snapshot.mandatoryTargetHealthy(), SymptomKind.CONNECTION_REFUSED);
    }

    public When no_units_have_failed() {
      return check("failed units", snapshot.failedUnits() == 0, SymptomKind.CONNECTION_REFUSED);
    }

    /**
     * Record the facet's observation, then fail-fast if not ready: an ok facet records an ok wire
     * and returns; a not-ready facet records a failed wire carrying the typed symptom (the doctor's
     * routing key) and throws so jGiven marks the step FAILED.
     */
    private When check(String facet, boolean ready, SymptomKind failureSymptom) {
      record(facet, ready, failureSymptom);
      if (!ready) {
        throw new AssertionError(facet + ": not ready");
      }
      return self();
    }

    private void record(String facet, boolean ready, SymptomKind failureSymptom) {
      if (ready) {
        observations.add(
            new ObservationWire("ok", facet, Optional.empty(), Map.of("facet", facet)));
        return;
      }
      observations.add(
          new ObservationWire(
              "failed",
              facet + ": not ready",
              Optional.of(failureSymptom),
              Map.of("facet", facet)));
    }
  }

  /**
   * Then: the systemd adapter is reachable — reached only once every facet passed (a failing facet
   * throws in the When), the readable closing line, not where evaluation happens.
   */
  public static class Then extends Stage<Then> {

    public Then the_systemd_adapter_is_reachable() {
      return self();
    }
  }
}
