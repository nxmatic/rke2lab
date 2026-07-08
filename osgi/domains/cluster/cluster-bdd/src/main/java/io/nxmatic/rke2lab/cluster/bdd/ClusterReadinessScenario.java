package io.nxmatic.rke2lab.cluster.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.Quoted;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import com.tngtech.jgiven.junit5.JGivenExtension;
import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.cluster.port.ClusterReadinessContact;
import io.nxmatic.rke2lab.cluster.port.ClusterReadinessPhase;
import io.nxmatic.rke2lab.cluster.port.ControllerRef;
import io.nxmatic.rke2lab.doctor.port.ConsultingService;
import io.nxmatic.rke2lab.world.gateway.codec.DocumentCodec;
import io.nxmatic.rke2lab.world.gateway.port.Checkpoint;
import io.nxmatic.rke2lab.world.gateway.port.Coordinate;
import io.nxmatic.rke2lab.world.gateway.port.Document;
import io.nxmatic.rke2lab.world.gateway.port.Domain;
import io.nxmatic.rke2lab.world.gateway.port.ObservationWire;
import io.nxmatic.rke2lab.world.gateway.port.ReadinessCheckpoint;
import io.nxmatic.rke2lab.world.gateway.port.SymptomKind;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

/**
 * The cluster-readiness checkpoint, a production jGiven scenario told in the CLUSTER DOMAIN's own
 * vocabulary — {@link ClusterReadinessContact} probed across its {@link ClusterReadinessPhase}s
 * over a kubeconfig, no host/Pulumi type. Played IN-CONTAINER by the engine so the runbook shows a
 * real node of the OSGi world; it lives in {@code cluster-bdd} (only ports, no sealed internal),
 * not a {@code -test} fragment (it is live seeding logic).
 *
 * <p>It resolves its collaborator — the {@link ClusterReadinessContact} — from its OWN bundle's
 * service registry ({@link FrameworkUtil}); the scenario is identical live and in test, only who
 * published the contact differs (the live {@code KubectlClusterContact}, or a mock a test seeds
 * into the registry before playing). The phases form a strict chain (kubeconfig → API →
 * controllers): a not-ready phase throws, jGiven marks it FAILED and skips the downstream chained
 * steps, so the runbook shows exactly where readiness broke.
 */
@ExtendWith(JGivenExtension.class)
public class ClusterReadinessScenario
    extends ScenarioTestBase<
        ClusterReadinessScenario.Given,
        ClusterReadinessScenario.When,
        ClusterReadinessScenario.Then> {

  // Scaffolding for increment 1: the front-door harvests the played model off this holder. REPLACED
  // in increment 2 by inject-the-model (the driver seeds its own ReportModel via the session store,
  // jGiven writes into it), when the cross-world graft lifts the model into the host runbook. An
  // initialized holder (never null) so the null-hygiene gate stays green; the run fills it.
  private static final AtomicReference<ReportModel> LAST_RUNBOOK = new AtomicReference<>();

  // The doctor consultations the run raised on a failing phase, harvested by the front-door into
  // the
  // envelope alongside the runbook. Empty when every phase passed (a healthy run consults no one).
  private static final AtomicReference<List<io.nxmatic.rke2lab.world.gateway.port.Document>>
      LAST_CONSULTATIONS = new AtomicReference<>(List.of());

  static ReportModel lastRunbook() {
    return Objects.requireNonNull(
        LAST_RUNBOOK.get(), "the scenario has not played yet — no runbook to harvest");
  }

  static List<io.nxmatic.rke2lab.world.gateway.port.Document> lastConsultations() {
    return LAST_CONSULTATIONS.get();
  }

  private final Scenario<Given, When, Then> scenario = createScenario();

  @Override
  public Scenario<Given, When, Then> getScenario() {
    return scenario;
  }

  @Test
  void the_cluster_becomes_ready() {
    final ClusterReadinessContact contact = resolveContact();
    final Map<ClusterReadinessPhase, ObservationWire> observations =
        new EnumMap<>(ClusterReadinessPhase.class);
    given().the_cluster("seed", kubeconfig()).and().probed_through(contact, observations);
    when()
        .the_kubeconfig_is_published()
        .and()
        .the_api_is_ready()
        .and()
        .the_required_controllers_are_effective();
    then().the_cluster_is_ready();
    LAST_RUNBOOK.set(getScenario().getModel());
    LAST_CONSULTATIONS.set(consultOnFailure(observations));
  }

  /**
   * The domain consults the doctor ITSELF on a failing phase (fork B: the checkpoint owns its
   * consult, not the host). A phase reported not-ready recorded its {@link ObservationWire}
   * carrying a typed {@link SymptomKind}; if any is non-ok, resolve the doctor's {@link
   * ConsultingService} from THIS bundle's registry — the same way the contact is resolved — build
   * the {@code readiness-checkpoint} Document around the observations, and consult. The returned
   * {@code consultation} Documents ride the envelope back to the host, which records them into its
   * shared log (it no longer computes the diagnosis, only renders it). A healthy run raised no
   * symptom, so it consults no one and returns an empty list.
   */
  private List<Document> consultOnFailure(
      Map<ClusterReadinessPhase, ObservationWire> observations) {
    final boolean anySymptom =
        observations.values().stream().anyMatch(o -> o.symptom().isPresent());
    if (!anySymptom) {
      return List.of();
    }
    return resolveDoctor()
        .map(doctor -> List.of(doctor.consult(consultCheckpoint(observations))))
        .orElseGet(List::of);
  }

  /** The {@code readiness-checkpoint} Document the domain hands the doctor — its observations. */
  private static Document consultCheckpoint(
      Map<ClusterReadinessPhase, ObservationWire> observations) {
    final ReadinessCheckpoint checkpoint =
        new ReadinessCheckpoint(
            Checkpoint.CLUSTER_READINESS.slug(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            List.copyOf(observations.values()));
    final DocumentCodec codec = new DocumentCodec();
    return new Document(
        Domain.DOCTOR.slug(), Coordinate.READINESS_CHECKPOINT.slug(), codec.encode(checkpoint));
  }

  /**
   * The kubeconfig the checkpoint reads the cluster through. A published path in live; in the
   * offline scenario the mock contact ignores it, so a fixed marker suffices to satisfy the
   * kubeconfig-published phase.
   */
  private static Path kubeconfig() {
    return Path.of("/srv/host/kubeconfig");
  }

  /**
   * Resolve the cluster contact from THIS bundle's registry — the in-container lookup ({@code
   * FrameworkUtil.getBundle(this).getBundleContext()}). A test seeds a mock under the same
   * interface before playing; live, SCR has published {@code KubectlClusterContact}.
   */
  private ClusterReadinessContact resolveContact() {
    final BundleContext context = bundleContext();
    final ServiceReference<ClusterReadinessContact> ref =
        Objects.requireNonNull(
            context.getServiceReference(ClusterReadinessContact.class),
            "no ClusterReadinessContact in the registry (live edge or test mock must publish one)");
    return context.getService(ref);
  }

  /**
   * Resolve the doctor's {@link ConsultingService} from THIS bundle's registry, or {@link
   * Optional#empty()} if none is published — a real runtime condition (a world booted without the
   * doctor), so a failing phase without a doctor degrades to no consultation rather than a crash.
   * Unlike the contact, the doctor is OPTIONAL: the checkpoint still fails, just unconsulted.
   */
  private Optional<ConsultingService> resolveDoctor() {
    final BundleContext context = bundleContext();
    return Optional.ofNullable(context.getServiceReference(ConsultingService.class))
        .map(context::getService);
  }

  /**
   * THIS bundle's context — the in-container registry the scenario resolves its collaborators from.
   */
  private BundleContext bundleContext() {
    return Objects.requireNonNull(
            FrameworkUtil.getBundle(getClass()),
            "cluster-bdd is not bundle-loaded — the scenario must play in-container")
        .getBundleContext();
  }

  /** Given: the kubeconfig to read the cluster through, the controllers to wait on, the contact. */
  public static class Given extends Stage<Given> {

    @ProvidedScenarioState Path kubeconfig;
    @ProvidedScenarioState List<ControllerRef> controllers = List.of();
    @ProvidedScenarioState ClusterReadinessContact contact;

    /**
     * The per-phase observations the When records — the material the domain's own consult reads.
     */
    @ProvidedScenarioState Map<ClusterReadinessPhase, ObservationWire> observations;

    public Given the_cluster(@Quoted String name, @Hidden Path kubeconfig) {
      this.kubeconfig = kubeconfig;
      return self();
    }

    @Hidden
    public Given probed_through(
        ClusterReadinessContact contact, Map<ClusterReadinessPhase, ObservationWire> observations) {
      this.contact = contact;
      this.observations = observations;
      return self();
    }
  }

  /**
   * When: each readiness phase is its own step, chained in canonical order. Each phase records its
   * {@link ObservationWire} (ok, or failed with a typed {@link SymptomKind}) into the shared map,
   * then a not-ready phase throws — jGiven marks its step FAILED and skips the downstream chained
   * steps. Fail-fast is the chain's own semantics; the recorded observations are what the domain's
   * own doctor consult reads after the play.
   */
  public static class When extends Stage<When> {

    @ExpectedScenarioState Path kubeconfig;
    @ExpectedScenarioState List<ControllerRef> controllers;
    @ExpectedScenarioState ClusterReadinessContact contact;
    @ExpectedScenarioState Map<ClusterReadinessPhase, ObservationWire> observations;

    public When the_kubeconfig_is_published() {
      return check(
          ClusterReadinessPhase.KUBECONFIG_PUBLISHED,
          kubeconfig != null,
          SymptomKind.KUBECONFIG_MISSING);
    }

    public When the_api_is_ready() {
      return check(
          ClusterReadinessPhase.API_READY,
          contact.isApiReady(kubeconfig),
          SymptomKind.API_NOT_READY);
    }

    public When the_required_controllers_are_effective() {
      return check(
          ClusterReadinessPhase.CONTROLLERS_EFFECTIVE,
          contact.areControllersEffective(kubeconfig, controllers),
          SymptomKind.CONTROLLER_NOT_READY);
    }

    /**
     * Record the phase's observation, then fail-fast if not ready: an ok phase records an ok wire
     * and returns; a not-ready phase records a failed wire carrying the typed symptom (the doctor's
     * routing key) and throws so jGiven marks the step FAILED. The map is the material the domain's
     * consult reads.
     */
    private When check(ClusterReadinessPhase phase, boolean ready, SymptomKind failureSymptom) {
      if (ready) {
        observations.put(
            phase,
            new ObservationWire(
                "ok", phase.label(), Optional.empty(), Map.of("phase", phase.name())));
        return self();
      }
      observations.put(
          phase,
          new ObservationWire(
              "failed",
              phase.label() + ": not ready",
              Optional.of(failureSymptom),
              Map.of("phase", phase.name())));
      throw new AssertionError(phase.label() + ": not ready");
    }
  }

  /**
   * Then: the cluster is ready — reached only once every phase passed (a failing phase throws in
   * the When), the readable closing line, not where evaluation happens.
   */
  public static class Then extends Stage<Then> {

    public Then the_cluster_is_ready() {
      return self();
    }
  }
}
