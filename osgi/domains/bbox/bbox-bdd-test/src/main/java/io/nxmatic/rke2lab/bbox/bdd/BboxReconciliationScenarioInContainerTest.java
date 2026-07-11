package io.nxmatic.rke2lab.bbox.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.tngtech.jgiven.report.json.ScenarioJsonReader;
import com.tngtech.jgiven.report.model.ExecutionStatus;
import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.bbox.core.BboxAction;
import io.nxmatic.rke2lab.bbox.core.BboxReconciler;
import io.nxmatic.rke2lab.bbox.core.BboxReservationRequest;
import io.nxmatic.rke2lab.bbox.core.BboxRowOutcome;
import io.nxmatic.rke2lab.doctor.port.ConsultingService;
import io.nxmatic.rke2lab.doctor.records.Checkpoint;
import io.nxmatic.rke2lab.doctor.records.Consultation;
import io.nxmatic.rke2lab.doctor.records.DoctorCoordinate;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.RunGate;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;

/**
 * The in-container proof of the bbox scion, run WHERE the scenario lives (this passenger shares the
 * bbox-bdd host loader through the fragment). It registers the scion's collaborators — a mock
 * {@link BboxReconciler} and a mock {@link RunGate} (and, for the refusal case, a mock {@link
 * ConsultingService}) — into the SAME registry the scenario resolves from, then plays {@link
 * BboxBddScenarios#run()} (the production front-door) and asserts on the harvested envelope.
 *
 * <p>No seam, no system-export: because the fragment shares the bundle's classloader, the mock this
 * passenger registers is the same {@code Class} the scenario reads (unlike the out-of-container
 * shape, which registers on the host loader and needs the port system-exported). It is the {@code
 * HealthSystemContributionTest} shape — register in-container, then act, one method — applied to a
 * front-door play. Registrations are unregistered in a {@code finally} because the framework is
 * shared across the passenger's tests (an oldest-wins ranking tie would otherwise leak a mock).
 */
public class BboxReconciliationScenarioInContainerTest {

  private static final SeedCodec CODEC = new SeedCodec();

  /** The canonical run enumerates 2 clusters × 6 nodes = 12 desired reservations. */
  private static final int DESIRED_COUNT = 12;

  @Test
  void a_live_run_reconciles_every_row_green() throws Exception {
    // cultivating() true → the scion asks for a live apply; the mock reconciler matches every row.
    final String envelope = playWith(cultivatingGate(true), allMatching(), null);
    final ReportModel runbook = rebuild(envelope);

    assertNotNull(runbook, "the front-door harvested the played model");
    assertEquals(1, runbook.getScenarios().size(), "one scenario played");
    assertEquals(
        ExecutionStatus.SUCCESS,
        runbook.getScenarios().get(0).getExecutionStatus(),
        "every desired row reconciled → the scenario plays green");
    assertTrue(
        consultationsOf(envelope).isEmpty(), "a healthy run refused no row, so it consults no one");
  }

  @Test
  void a_preview_run_asks_for_a_dry_run() throws Exception {
    // cultivating() false → the scion derives dryRun=true; the mock records the flag it was called
    // with and returns WOULD_* outcomes (the router is never mutated). The recorder proves the
    // SCION consulted the gate (A2) and passed the dry-run down.
    final RecordingReconciler reconciler = wouldCreate();
    final String envelope = playWith(cultivatingGate(false), reconciler, null);

    assertEquals(
        ExecutionStatus.SUCCESS,
        rebuild(envelope).getScenarios().get(0).getExecutionStatus(),
        "a dry-run that would create every row still reconciles cleanly (no FAILED row)");
    assertTrue(reconciler.lastDryRun, "the scion consulted the RunGate and asked for a dry-run");
    assertTrue(
        consultationsOf(envelope).isEmpty(),
        "WOULD_CREATE is not a refusal, so it consults no one");
  }

  @Test
  void a_refused_row_makes_the_domain_consult_the_doctor_itself() throws Exception {
    // A FAILED row is a symptom; the scion resolves the doctor from its OWN registry and consults —
    // the consultation rides the envelope back, the host no longer computes it (fork B).
    final RecordingDoctor doctor = new RecordingDoctor();
    final String envelope = playWith(cultivatingGate(true), oneRefused(), doctor);

    assertEquals(
        ExecutionStatus.FAILED,
        rebuild(envelope).getScenarios().get(0).getExecutionStatus(),
        "a refused reservation row fails the checkpoint (the row is surfaced, not dropped)");
    assertEquals(1, doctor.consultedCheckpoints.size(), "the domain consulted the doctor once");
    assertTrue(
        doctor.consultedCheckpoints.get(0).contains("reservation-refused"),
        "the consult carries the typed symptom the doctor routes on (to the NETWORK domain)");

    final List<JsonNode> consultations = consultationsOf(envelope);
    assertEquals(1, consultations.size(), "the consultation rides the envelope back to the host");
    assertEquals(
        "bbox-reservations",
        CODEC.decode(consultations.get(0).path("payload").asText()).path("scenarioId").asText(),
        "the consultation names the checkpoint the host joins on");
  }

  /**
   * Register the mock collaborators into THIS bundle's registry, play the scenario in-container
   * through the front-door, and return its serialized envelope. Registrations are removed in the
   * {@code finally} so each test plays against exactly its own mocks.
   */
  private static String playWith(RunGate gate, BboxReconciler reconciler, ConsultingService doctor)
      throws Exception {
    final BundleContext context = FrameworkUtil.getBundle(BboxBddTests.class).getBundleContext();
    final List<ServiceRegistration<?>> registrations = new ArrayList<>();
    registrations.add(context.registerService(RunGate.class, gate, new Hashtable<>()));
    registrations.add(context.registerService(BboxReconciler.class, reconciler, new Hashtable<>()));
    if (doctor != null) {
      registrations.add(
          context.registerService(ConsultingService.class, doctor, new Hashtable<>()));
    }
    try {
      return BboxBddScenarios.run();
    } finally {
      registrations.forEach(ServiceRegistration::unregister);
    }
  }

  /** The consultations the run raised, read off the envelope with the host's own codec. */
  private static List<JsonNode> consultationsOf(String envelopeJson) {
    final List<JsonNode> consultations = new ArrayList<>();
    CODEC.decode(envelopeJson).path("consultations").forEach(consultations::add);
    return consultations;
  }

  /**
   * Rebuild a host-realm {@link ReportModel} from the front-door's serialized envelope: read the
   * {@code runbook} field with the host's own jackson, then round it through {@link
   * ScenarioJsonReader} into a model of THIS realm. No jGiven type crosses live — the envelope is
   * flat JSON.
   */
  private static ReportModel rebuild(String envelopeJson) throws Exception {
    final String runbookJson = CODEC.decode(envelopeJson).path("runbook").asText();
    final File tmp = Files.createTempFile("bbox-reconciliation-runbook", ".json").toFile();
    tmp.deleteOnExit();
    Files.writeString(tmp.toPath(), runbookJson);
    return new ScenarioJsonReader().apply(tmp);
  }

  /** A RunGate the test pins to a chosen cultivating value. */
  private static RunGate cultivatingGate(boolean cultivating) {
    return () -> cultivating;
  }

  /** A reconciler that matches every desired row (a healthy live run). */
  private static BboxReconciler allMatching() {
    return (baseUri, adminPassword, dryRun, requests) ->
        requests.stream().map(r -> outcome(r, BboxAction.MATCHING)).toList();
  }

  /** A reconciler that would create every row and records the dry-run flag it was called with. */
  private static RecordingReconciler wouldCreate() {
    return new RecordingReconciler(BboxAction.WOULD_CREATE);
  }

  /** A reconciler whose first row is refused (FAILED) and the rest match. */
  private static BboxReconciler oneRefused() {
    return (baseUri, adminPassword, dryRun, requests) -> {
      final List<BboxRowOutcome> outcomes = new ArrayList<>(requests.size());
      for (int i = 0; i < requests.size(); i++) {
        outcomes.add(outcome(requests.get(i), i == 0 ? BboxAction.FAILED : BboxAction.MATCHING));
      }
      return outcomes;
    };
  }

  private static BboxRowOutcome outcome(BboxReservationRequest request, BboxAction action) {
    return new BboxRowOutcome(
        request.cluster(),
        request.node(),
        action,
        request.mac(),
        request.ip(),
        request.hostname(),
        OptionalInt.empty(),
        Optional.empty(),
        Optional.empty(),
        action == BboxAction.FAILED
            ? Optional.of("router refused the reservation")
            : Optional.empty());
  }

  /** A reconciler that records the dry-run flag the scion passed — proving the A2 gate consult. */
  private static final class RecordingReconciler implements BboxReconciler {
    private final BboxAction action;
    boolean lastDryRun;

    RecordingReconciler(BboxAction action) {
      this.action = action;
    }

    @Override
    public List<BboxRowOutcome> reconcile(
        URI baseUri, String adminPassword, boolean dryRun, List<BboxReservationRequest> requests) {
      this.lastDryRun = dryRun;
      return requests.stream().map(r -> outcome(r, action)).toList();
    }
  }

  /**
   * A mock doctor — records each consulted checkpoint's payload (so the test asserts the domain
   * routed the typed symptom) and returns a minimal {@code consultation} SeedEnvelope naming the
   * checkpoint the host joins on. The test seeds it into the registry before playing.
   */
  private static final class RecordingDoctor implements ConsultingService {
    final List<String> consultedCheckpoints = new ArrayList<>();

    @Override
    public SeedEnvelope consult(SeedEnvelope checkpoint) {
      consultedCheckpoints.add(checkpoint.payload());
      final Consultation reply =
          new Consultation(
              Checkpoint.BBOX_RESERVATIONS.slug(),
              "the bbox reservations checkpoint was consulted",
              "",
              Map.of(),
              List.of());
      return SeedEnvelope.of(DoctorCoordinate.CONSULTATION, CODEC.encode(reply));
    }

    @Override
    public void reviewDrift() {}
  }
}
