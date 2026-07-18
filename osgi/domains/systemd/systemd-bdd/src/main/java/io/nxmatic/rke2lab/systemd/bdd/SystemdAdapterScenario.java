package io.nxmatic.rke2lab.systemd.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import io.nxmatic.rke2lab.doctor.contract.Checkpoint;
import io.nxmatic.rke2lab.doctor.contract.ConsultingService;
import io.nxmatic.rke2lab.doctor.contract.DoctorCoordinate;
import io.nxmatic.rke2lab.doctor.contract.ObservationWire;
import io.nxmatic.rke2lab.doctor.contract.ReadinessCheckpoint;
import io.nxmatic.rke2lab.doctor.contract.SymptomKind;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ConsultationSource;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.OsgiService;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.SeedScenario;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.nxmatic.rke2lab.systemd.contract.SystemdProbeRequest;
import io.nxmatic.rke2lab.systemd.contract.SystemdRuntimeProbe;
import io.nxmatic.rke2lab.systemd.contract.SystemdStatusSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

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
 * of readable assertions rather than a chain of contact calls. Its collaborator — the {@link
 * SystemdRuntimeProbe} — is INJECTED from its OWN bundle's registry by the {@link OsgiService}
 * bridge; the scenario is identical live and in test, only who published the probe differs (the
 * live {@code DbusSystemdProbe}, or a mock a test seeds into the registry before playing). A
 * not-ready facet throws, jGiven marks it FAILED and skips the downstream chained assertions, so
 * the runbook shows exactly which systemd fact broke.
 *
 * <p>The endpoint the probe opens is described by a fixed {@link SystemdProbeRequest} marker (as
 * {@code ClusterReadinessScenario} uses a fixed kubeconfig path): the offline mock ignores it, and
 * the live endpoint-config plumbing is the same deferral the cluster twin makes.
 */
@SeedScenario
public class SystemdAdapterScenario
    extends ScenarioTestBase<
        SystemdAdapterScenario.Given, SystemdAdapterScenario.When, SystemdAdapterScenario.Then>
    implements ConsultationSource, ScenarioPlayer.Playable {

  private final Scenario<Given, When, Then> scenario = createScenario();

  // Injected by the OsgiServiceExtension from THIS bundle's registry before the body (the
  // @Reference a Jupiter-instantiated scenario cannot have). Uniform Optional (never null — the
  // bridge owns presence): the probe awaits SCR (orElseThrow never fires — the bridge throws first
  // if absent); the doctor is await=false, a snapshot, empty when a world booted without it.
  @OsgiService private Optional<SystemdRuntimeProbe> probe = Optional.empty();

  @OsgiService(await = false)
  private Optional<ConsultingService> doctor = Optional.empty();

  // The consultations the run raised on a failing facet — the ScenarioOutcomeExtension PULLS them
  // (ConsultationSource) at the run boundary. Set in the @Test after the body (jGiven defers a
  // failing facet's throw to scenario-end, so it still reaches this); empty until then.
  private List<SeedEnvelope> consultations = List.of();

  @Override
  public Scenario<Given, When, Then> getScenario() {
    return scenario;
  }

  @Override
  public List<SeedEnvelope> consultations() {
    return consultations;
  }

  @Test
  void the_systemd_adapter_becomes_reachable() {
    final List<ObservationWire> observations = new ArrayList<>();
    given().the_seed_node("seed").and().probed_through(probe.orElseThrow(), observations);
    when()
        .the_systemd_endpoint_is_probed()
        .and()
        .the_mandatory_target_is_healthy()
        .and()
        .no_units_have_failed();
    then().the_systemd_adapter_is_reachable();
    this.consultations = consultOnFailure(observations);
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
    return doctor
        .map(consulting -> List.of(consulting.consult(consultCheckpoint(observations))))
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
