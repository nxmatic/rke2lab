package io.nxmatic.rke2lab.controlplane.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.ScenarioStage;
import com.tngtech.jgiven.annotation.ScenarioState;
import io.nxmatic.rke2lab.cluster.port.ClusterReadinessContact;
import io.nxmatic.rke2lab.cluster.port.ClusterReadinessPhase;
import io.nxmatic.rke2lab.controlplane.incus.IncusResourceBootstrap.BootstrapResult;
import io.nxmatic.rke2lab.controlplane.readiness.ClusterBootstrapReadinessVerifier;
import io.nxmatic.rke2lab.controlplane.readiness.ClusterBootstrapReadinessVerifier.VerificationResult;
import io.nxmatic.rke2lab.controlplane.systemd.SeedSystemdAdapterRuntimeStatusSnapshot;
import io.nxmatic.rke2lab.doctor.port.ConsultingService;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.OsgiConnection;
import io.nxmatic.rke2lab.systemd.port.SystemdRuntimeProbe;
import io.nxmatic.rke2lab.world.gateway.codec.DocumentCodec;
import io.nxmatic.rke2lab.world.gateway.port.Checkpoint;
import io.nxmatic.rke2lab.world.gateway.port.Consultation;
import io.nxmatic.rke2lab.world.gateway.port.Coordinate;
import io.nxmatic.rke2lab.world.gateway.port.Document;
import io.nxmatic.rke2lab.world.gateway.port.Domain;
import io.nxmatic.rke2lab.world.gateway.port.ObservationWire;
import io.nxmatic.rke2lab.world.gateway.port.ReadinessCheckpoint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * The cluster-readiness checkpoint, as a phase — island 2. It plays the reused {@link
 * ClusterReadinessScenario} readiness phases through native {@code @NestedSteps} composition, and
 * provides the {@link VerificationResult} the resources fan-in consumes.
 *
 * <p>The dependency on systemd-adapter is EXPLICIT, not replayed: the seed scenario plays systemd
 * as a top-level phase before this one, so the dependency is the top-level order plus the {@code
 * adapterLaunch} state this stage consumes (whereas the isolated {@code ClusterReadinessTopic}
 * still re-narrates it via {@code the_systemd_adapter_dependency_is_satisfied}). The readiness
 * phases form a strict chain (kubeconfig → API → controllers); a failing phase throws so the
 * runbook shows where it broke, then the stage consults the doctor and projects the failed {@link
 * VerificationResult}.
 */
public class ClusterReadinessStage extends Stage<ClusterReadinessStage> {

  private static final Checkpoint DOMAIN_CHECKPOINT = Checkpoint.CLUSTER_READINESS;
  private static final String SCENARIO_ID = DOMAIN_CHECKPOINT.slug();

  @ExpectedScenarioState HostFacts hostFacts;
  @ExpectedScenarioState OsgiConnection connection;

  /** The upstream systemd-adapter result — the explicit dependency edge (produced by its phase). */
  @ExpectedScenarioState(resolution = ScenarioState.Resolution.NAME)
  @MonotonicNonNull
  Map<String, Object> adapterLaunch;

  /** The incus provisioning outcome — its deployment timestamp stamps the consult checkpoint. */
  @ExpectedScenarioState @MonotonicNonNull Optional<BootstrapResult> bootstrap;

  /**
   * Injected in tests (a per-phase fake/simulated probe); null in the live boot → {@link
   * #liveProbe()}.
   */
  @ExpectedScenarioState @MonotonicNonNull ClusterReadinessProbe clusterProbe;

  @ScenarioStage ClusterReadinessScenario.Given given;
  @ScenarioStage ClusterReadinessScenario.When when;
  @ScenarioStage ClusterReadinessScenario.Then then;

  @ProvidedScenarioState @MonotonicNonNull VerificationResult verification;

  private final DocumentCodec codec = new DocumentCodec();

  @As("the cluster becomes ready")
  public ClusterReadinessStage the_cluster_is_verified_ready() {
    if (!hostFacts.options().readinessEnabled()) {
      log("cluster readiness disabled by configuration");
      this.verification =
          ClusterBootstrapReadinessVerifier.skipped(
              hostFacts.policy(), hostFacts.readinessLogger());
      return self();
    }

    final Map<ClusterReadinessPhase, ObservationView> phaseObservations =
        new EnumMap<>(ClusterReadinessPhase.class);
    final ClusterReadinessProbe resolved = resolveProbe();
    final ClusterReadinessProbe capturing =
        (cfg, phase) -> {
          final ObservationView produced = resolved.probe(cfg, phase);
          phaseObservations.put(phase, produced);
          return produced;
        };

    try {
      given
          .the_cluster(hostFacts.config().clusterName(), hostFacts.config())
          .with_phase_probe(capturing);
      when.the_kubeconfig_is_published()
          .and()
          .the_api_is_ready()
          .and()
          .the_required_controllers_are_effective();
      then.the_cluster_is_ready();
      log("✓ " + SCENARIO_ID + " ready: kubeconfig published, API ready, controllers effective");
      this.verification = ClusterBootstrapReadinessVerifier.ready(hostFacts.policy());
      return self();
    } catch (Throwable failure) {
      consultDoctor(phaseObservations);
      this.verification = failedProjection(phaseObservations);
      return self();
    }
  }

  private ClusterReadinessProbe resolveProbe() {
    return clusterProbe != null ? clusterProbe : liveProbe();
  }

  /** The live probe backed by the cluster contact + runtime-status snapshot from the registry. */
  private ClusterReadinessProbe liveProbe() {
    final SeedSystemdAdapterRuntimeStatusSnapshot snapshot =
        new SeedSystemdAdapterRuntimeStatusSnapshot(
            connection.awaitService(SystemdRuntimeProbe.class, 5000));
    final ClusterReadinessContact contact =
        connection.awaitService(ClusterReadinessContact.class, 5000);
    return new LiveClusterReadinessProbe(
        hostFacts.policy(), snapshot, contact, hostFacts.readinessLogger());
  }

  private void consultDoctor(Map<ClusterReadinessPhase, ObservationView> phaseObservations) {
    final ConsultingService doctor = connection.awaitService(ConsultingService.class, 5000);
    if (doctor == null
        || phaseObservations.values().stream().noneMatch(o -> o.symptom().isPresent())) {
      return;
    }
    final Document consultation = doctor.consult(consultCheckpoint(phaseObservations.values()));
    log("⚕ " + codec.decode(consultation, Consultation.class).narration());
    hostFacts.consultations().record(consultation);
  }

  private Document consultCheckpoint(Iterable<ObservationView> observations) {
    final List<ObservationWire> wires = new ArrayList<>();
    observations.forEach(observation -> wires.add(observation.toWire()));
    final Optional<Instant> recordedAt =
        bootstrap != null
            ? bootstrap.map(result -> result.deployment().timestamp())
            : Optional.empty();
    final ReadinessCheckpoint checkpoint =
        new ReadinessCheckpoint(SCENARIO_ID, Optional.empty(), Optional.empty(), recordedAt, wires);
    return new Document(
        Domain.DOCTOR.slug(), Coordinate.READINESS_CHECKPOINT.slug(), codec.encode(checkpoint));
  }

  private VerificationResult failedProjection(
      Map<ClusterReadinessPhase, ObservationView> phaseObservations) {
    final boolean kubeconfig =
        phaseOk(phaseObservations, ClusterReadinessPhase.KUBECONFIG_PUBLISHED);
    final boolean api = phaseOk(phaseObservations, ClusterReadinessPhase.API_READY);
    final boolean controllers =
        phaseOk(phaseObservations, ClusterReadinessPhase.CONTROLLERS_EFFECTIVE);
    final String summary =
        phaseObservations.values().stream()
            .filter(observation -> !observation.isOk())
            .map(ObservationView::summary)
            .findFirst()
            .orElse("cluster readiness failed");
    return ClusterBootstrapReadinessVerifier.failed(
        kubeconfig, api, controllers, summary, hostFacts.policy());
  }

  private static boolean phaseOk(
      Map<ClusterReadinessPhase, ObservationView> phaseObservations, ClusterReadinessPhase phase) {
    final ObservationView observation = phaseObservations.get(phase);
    return observation != null && observation.isOk();
  }

  private void log(String message) {
    if (hostFacts.readinessLogger() != null) {
      hostFacts.readinessLogger().accept(message);
    }
  }
}
