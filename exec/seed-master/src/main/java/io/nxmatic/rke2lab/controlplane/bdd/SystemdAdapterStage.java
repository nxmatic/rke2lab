package io.nxmatic.rke2lab.controlplane.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.ScenarioStage;
import com.tngtech.jgiven.annotation.ScenarioState.Resolution;
import io.nxmatic.rke2lab.controlplane.systemd.SeedSystemdAdapterEndpointGate;
import io.nxmatic.rke2lab.controlplane.systemd.SeedSystemdAdapterRuntimeStatusSnapshot;
import io.nxmatic.rke2lab.doctor.port.ConsultingService;
import io.nxmatic.rke2lab.incus.port.IncusInstanceContact;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.OsgiConnection;
import io.nxmatic.rke2lab.systemd.port.SystemdRuntimeProbe;
import io.nxmatic.rke2lab.world.gateway.codec.DocumentCodec;
import io.nxmatic.rke2lab.world.gateway.port.Action;
import io.nxmatic.rke2lab.world.gateway.port.Checkpoint;
import io.nxmatic.rke2lab.world.gateway.port.Consultation;
import io.nxmatic.rke2lab.world.gateway.port.Coordinate;
import io.nxmatic.rke2lab.world.gateway.port.Document;
import io.nxmatic.rke2lab.world.gateway.port.Domain;
import io.nxmatic.rke2lab.world.gateway.port.ReadinessCheckpoint;
import io.nxmatic.rke2lab.world.gateway.port.ReadinessVerdict;
import io.nxmatic.rke2lab.world.gateway.port.SeedBroker;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The systemd-adapter readiness checkpoint, as a phase: it plays the reused {@link
 * SystemdAdapterScenario} script through native {@code @NestedSteps} composition — play → capture →
 * on success provides the observation into the DAG; on failure consults the doctor, asks the
 * readiness authority for the verdict, and either aborts the seed ({@link SeedAborted}) or
 * continues DEGRADED.
 *
 * <p>The runbook is the driver's injected model, the consultation log is a host fact, and preview
 * is jGiven-native (Task 7). The OSGi services are resolved from the connection, not held as
 * constructor fields.
 */
public class SystemdAdapterStage extends Stage<SystemdAdapterStage> {

  private static final Checkpoint DOMAIN_CHECKPOINT = Checkpoint.SYSTEMD_ADAPTER;
  private static final String SCENARIO_ID = DOMAIN_CHECKPOINT.slug();

  @ExpectedScenarioState HostFacts hostFacts;
  @ExpectedScenarioState OsgiConnection connection;

  /**
   * The endpoint probe override — {@link Optional#empty()} in the live boot (the stage resolves the
   * live probe from the registry, {@link #liveProbe()}), present when a test injects a
   * reachable/failing fake (later a preview-simulated incident). Optional by nature, never null: a
   * test fake lets the scenario play without the host-side {@code incus exec} the live gate still
   * runs (the un-migrated instance-reachability axis, see {@code SeedSystemdAdapterEndpointGate}).
   * {@code Resolution.NAME} because its erased type ({@code Optional}) would otherwise collide with
   * other {@code Optional} scenario-state.
   */
  @ExpectedScenarioState(resolution = Resolution.NAME)
  Optional<SystemdAdapterProbe> injectedProbe = Optional.empty();

  @ScenarioStage SystemdAdapterScenario.Given given;
  @ScenarioStage SystemdAdapterScenario.When when;
  @ScenarioStage SystemdAdapterScenario.Then then;

  @ProvidedScenarioState(resolution = Resolution.NAME)
  Map<String, Object> adapterLaunch;

  private final DocumentCodec codec = new DocumentCodec();

  @As("the systemd adapter is launched")
  public SystemdAdapterStage the_systemd_adapter_is_launched() {
    final SystemdAdapterProbe resolved = resolveProbe();
    final ObservationView[] observationHolder = new ObservationView[1];
    final SystemdAdapterProbe probe =
        cfg -> {
          final ObservationView produced = resolved.probe(cfg);
          observationHolder[0] = produced;
          return produced;
        };

    try {
      given.the_seed_node(hostFacts.config().systemdAdapterDbusHost(), hostFacts.config());
      given.probed_by(probe);
      when.the_systemd_adapter_probe_runs();
      then.the_dbus_endpoint_responds();
      this.adapterLaunch = then.capturedObservation().toOutputMap();
      return self();
    } catch (Throwable failure) {
      return onFailure(failure, Optional.ofNullable(observationHolder[0]));
    }
  }

  /**
   * The probe to play: the injected one when the driver supplied it (test fake, later a preview
   * incident), else the live probe built from the registry. The injected path is how the happy path
   * plays offline — the live probe's endpoint gate still runs a host-side {@code incus exec} (the
   * un-migrated axis), so a test cannot go through it.
   */
  private SystemdAdapterProbe resolveProbe() {
    return injectedProbe.orElseGet(this::liveProbe);
  }

  /**
   * The live probe backed by the runtime-status snapshot + incus contact resolved from the
   * registry.
   */
  private SystemdAdapterProbe liveProbe() {
    final SeedSystemdAdapterRuntimeStatusSnapshot snapshot =
        new SeedSystemdAdapterRuntimeStatusSnapshot(
            connection.awaitService(SystemdRuntimeProbe.class, 5000));
    final SeedSystemdAdapterEndpointGate endpointGate =
        SeedSystemdAdapterEndpointGate.live(
            snapshot, connection.awaitService(IncusInstanceContact.class, 5000));
    return cfg -> endpointGate.ensureReachable(cfg, hostFacts.readinessLogger());
  }

  /**
   * On a failed probe: consult the doctor (the patient's narration + kept consultation), then ask
   * the readiness authority for the provisioning verdict. STOP throws to abort; CONTINUE_DEGRADED
   * sets a degraded observation and returns. The authority owns the severity vocabulary — the host
   * reads only the action field.
   */
  private SystemdAdapterStage onFailure(Throwable failure, Optional<ObservationView> captured) {
    consultDoctor(captured);

    final SeedBroker broker = connection.awaitService(SeedBroker.class, 5000);
    final Document verdict = broker.sow(Coordinate.READINESS_VERDICT, checkpointDocument());
    final Action action = codec.decode(verdict, ReadinessVerdict.class).action();
    if (action == Action.STOP) {
      log("✗ " + SCENARIO_ID + " FAILED, verdict=stop → stopping provisioning");
      throw new SeedAborted("systemd adapter", failure);
    }
    log("⚠ " + SCENARIO_ID + " FAILED, verdict=continue-degraded → continuing in DEGRADED mode");
    this.adapterLaunch = degradedObservation(failure).toOutputMap();
    return self();
  }

  private void consultDoctor(Optional<ObservationView> observation) {
    if (observation.isEmpty() || observation.get().symptom().isEmpty()) {
      return;
    }
    // The doctor is an OSGi service: absent (a timeout on the registry) → no consultation. Decorate
    // the registry lookup at the frontier; never reason on a raw null below.
    Optional.ofNullable(connection.awaitService(ConsultingService.class, 5000))
        .ifPresent(
            doctor -> {
              final Document consultation = doctor.consult(consultCheckpoint(observation.get()));
              log("⚕ " + codec.decode(consultation, Consultation.class).narration());
              hostFacts.consultations().record(consultation);
            });
  }

  private Document consultCheckpoint(ObservationView observation) {
    final ReadinessCheckpoint checkpoint =
        new ReadinessCheckpoint(
            SCENARIO_ID,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            List.of(observation.toWire()));
    return new Document(
        Domain.DOCTOR.slug(), Coordinate.READINESS_CHECKPOINT.slug(), codec.encode(checkpoint));
  }

  private Document checkpointDocument() {
    final ReadinessCheckpoint checkpoint =
        new ReadinessCheckpoint(
            SCENARIO_ID,
            Optional.of(true),
            hostFacts.policy().readiness().rawOverride(SCENARIO_ID),
            Optional.empty(),
            List.of());
    return new Document(
        Domain.DOCTOR.slug(), Coordinate.READINESS_CHECKPOINT.slug(), codec.encode(checkpoint));
  }

  private ObservationView degradedObservation(Throwable failure) {
    return ObservationView.of(
        "degraded",
        Optional.empty(),
        "dbusEndpoint="
            + hostFacts.config().systemdAdapterDbusHost()
            + ":"
            + hostFacts.config().systemdAdapterDbusPort()
            + " status=degraded ("
            + failure.getMessage()
            + ")",
        Map.of("source", "systemd-adapter-endpoint-gate", "probeMode", "systemd-adapter-runtime"));
  }

  private void log(String message) {
    hostFacts.readinessLogger().accept(message);
  }
}
