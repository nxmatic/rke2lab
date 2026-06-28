package io.nxmatic.rke2lab.doctor.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.nxmatic.rke2lab.doctor.port.ConsultingService;
import io.nxmatic.rke2lab.doctor.records.*;
import io.nxmatic.rke2lab.doctor.records.ClinicianId;
import io.nxmatic.rke2lab.doctor.records.ConsultationReport;
import io.nxmatic.rke2lab.doctor.records.Expectation;
import io.nxmatic.rke2lab.doctor.records.InterventionLedger;
import io.nxmatic.rke2lab.doctor.records.MedicalRecord;
import io.nxmatic.rke2lab.doctor.records.Observation;
import io.nxmatic.rke2lab.doctor.records.ProblemReview;
import io.nxmatic.rke2lab.doctor.records.Referral;
import io.nxmatic.rke2lab.doctor.records.ReferralReply;
import io.nxmatic.rke2lab.doctor.records.RemediationPlan;
import io.nxmatic.rke2lab.doctor.records.Specialty;
import io.nxmatic.rke2lab.doctor.records.Symptom;
import io.nxmatic.rke2lab.doctor.records.Visit;
import io.nxmatic.rke2lab.doctor.spi.Clinician;
import io.nxmatic.rke2lab.doctor.spi.Specialist;
import io.nxmatic.rke2lab.domain.annotations.Transitional;
import io.nxmatic.rke2lab.exchange.port.Coordinate;
import io.nxmatic.rke2lab.exchange.port.Domain;
import io.nxmatic.rke2lab.exchange.port.ExchangeCatalog;
import io.nxmatic.rke2lab.exchange.port.SymptomKind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The doctor's coordinator. When a checkpoint fails, the patient consults: the Generalist takes the
 * symptom + the captured {@link Observation}, and synthesizes a {@link RemediationPlan}.
 *
 * <ol>
 *   <li><b>firstLook</b> — retrieves the patient's {@link MedicalRecord} through its {@link
 *       ClinicalAccess} (the first act of the visit); it does not yet drive routing.
 *   <li><b>route</b> — route <em>deterministically</em> by symptom to the relevant specialties (a
 *       readable rules table — no inference). Irrelevant specialists stay dormant.
 *   <li><b>synthesize</b> — collect each routed specialist's prescription (if any) into one plan.
 * </ol>
 *
 * The Generalist reads records only through its {@link ClinicalAccess} (bound to its id by the
 * {@link HealthSystem} at employment); it holds no registry or patient directly.
 *
 * <p>The settled design splits this per-run object into an EMPLOYED practitioner (a stable
 * {@code @Component}) and a per-run Consultation value carrying the access — so every actor can be
 * a component of the system.
 */
@Transitional(
    to = "GeneralPractitioner + Consultation",
    spec = "practitioners-as-components-design.adoc")
public final class Generalist implements Clinician, ConsultingService {

  /** The Generalist's stable id — the grant policy's join key for the general practitioner. */
  public static final ClinicianId GENERALIST_ID = new ClinicianId("generalist");

  private final List<Specialist> specialists;
  private final ClinicalAccess access;
  private final DriftSpecialist driftSpecialist;
  private final ObjectMapper mapper = new ObjectMapper();

  private Generalist(
      List<Specialist> specialists, ClinicalAccess access, DriftSpecialist driftSpecialist) {
    this.specialists = List.copyOf(specialists);
    this.access = access;
    this.driftSpecialist = driftSpecialist;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {

    private List<Specialist> specialists = List.of();
    private ClinicalAccess access;
    private DriftSpecialist driftSpecialist;

    public Builder specialists(List<Specialist> specialists) {
      this.specialists = specialists;
      return this;
    }

    public Builder access(ClinicalAccess access) {
      this.access = access;
      return this;
    }

    public Builder driftSpecialist(DriftSpecialist driftSpecialist) {
      this.driftSpecialist = driftSpecialist;
      return this;
    }

    public Generalist build() {
      if (access == null) {
        throw new IllegalStateException("access is required");
      }
      // No ledger wired → the drift inference is computed and returned but not persisted, coherent
      // with the registry's no-backend degrade. The real writer is wired at the reconstruction
      // site.
      final DriftSpecialist drift =
          driftSpecialist != null ? driftSpecialist : new DriftSpecialist(intervention -> {});
      return new Generalist(specialists, access, drift);
    }
  }

  @Override
  public ClinicianId clinicianId() {
    return GENERALIST_ID;
  }

  /** The admitted patient's record, read through the held access. */
  @Override
  public MedicalRecord recordForCurrentPatient() {
    return access.record();
  }

  /**
   * The one-line consultation narration for the symptom, folded over the held patient's own record.
   * Twin of {@link #cohortFinding(Symptom)} — both render a line the consulting stages log.
   */
  @Override
  public String consultedLine(Symptom symptom) {
    final MedicalRecord record = access.record();
    return "consulted with "
        + record.visits().size()
        + " prior visit(s); "
        + symptom.id()
        + " seen "
        + record.historyOf(symptom).count()
        + "× before";
  }

  /**
   * A one-line cross-patient finding for the symptom, folded across the granted cohort. Empty
   * cohort (or no backend) yields a finding over just the current patient.
   */
  @Override
  public String cohortFinding(Symptom symptom) {
    final List<MedicalRecord> cohort = access.cohort();
    final long withSymptom = cohort.stream().filter(r -> r.historyOf(symptom).count() > 0).count();
    final long treatedAndResolved =
        cohort.stream()
            .filter(r -> r.efficacyOf(symptom, InterventionLedger.empty()).everWorked())
            .count();
    return "cohort: "
        + symptom.id()
        + " seen on "
        + withSymptom
        + " of "
        + cohort.size()
        + " patient(s); "
        + treatedAndResolved
        + " prior treatment(s) resolved it";
  }

  /**
   * Consult on a checkpoint {@link io.nxmatic.rke2lab.exchange.port.Document}: route its symptom +
   * observation to the specialists and synthesize the narration and the rendered AsciiDoc
   * diagnosis, returned as a {@code consultation} Document. The twin of the readiness authority's
   * assess — same checkpoint, the consulting concern rather than the provisioning verdict.
   */
  @Override
  public io.nxmatic.rke2lab.exchange.port.Document consult(
      io.nxmatic.rke2lab.exchange.port.Document checkpoint) {
    final JsonNode payload = parse(checkpoint.payload());
    final SymptomKind kind =
        SymptomKind.parse(payload.path(ExchangeCatalog.FIELD_SYMPTOM_KIND).asText()).orElseThrow();
    final Symptom symptom = toSymptom(kind);
    final Observation observation = observationFrom(payload, symptom);
    final RemediationPlan plan = consult(symptom, observation);
    final ConsultationReport report =
        new ConsultationReport(
            payload.path(ExchangeCatalog.FIELD_SCENARIO_ID).asText(), List.of(observation), plan);
    final Instant recordedAt =
        Instant.parse(payload.path(ExchangeCatalog.FIELD_RECORDED_AT).asText());

    final ObjectNode out = mapper.createObjectNode();
    out.put(
        ExchangeCatalog.FIELD_SCENARIO_ID,
        payload.path(ExchangeCatalog.FIELD_SCENARIO_ID).asText());
    out.put(ExchangeCatalog.FIELD_NARRATION, narrationLine(symptom));
    out.put(ExchangeCatalog.FIELD_DIAGNOSIS_ADOC, diagnosisBlock(plan));
    // Structured reconstruction sub-trees, in the EXACT shape the egress + readers use today:
    out.set(ConsultationReport.OUTPUT_KEY, mapper.valueToTree(report.toOutputMap()));
    out.set(
        Expectation.OUTPUT_KEY,
        mapper.valueToTree(
            report.expectations(recordedAt).stream().map(Expectation::toOutputMap).toList()));
    return new io.nxmatic.rke2lab.exchange.port.Document(
        Domain.DOCTOR.slug(), Coordinate.CONSULTATION.slug(), serialize(out));
  }

  /** Maps the wire SymptomKind to the internal Symptom enum. No default — drift forces update. */
  private static Symptom toSymptom(SymptomKind kind) {
    return switch (kind) {
      case CONNECTION_REFUSED -> Symptom.CONNECTION_REFUSED;
      case TIMEOUT -> Symptom.TIMEOUT;
      case KUBECONFIG_MISSING -> Symptom.KUBECONFIG_MISSING;
      case API_NOT_READY -> Symptom.API_NOT_READY;
      case CONTROLLER_NOT_READY -> Symptom.CONTROLLER_NOT_READY;
    };
  }

  /** Rebuilds the Observation OSGi-side from the checkpoint payload. */
  private static Observation observationFrom(JsonNode payload, Symptom symptom) {
    final String summary = payload.path(ExchangeCatalog.FIELD_SUMMARY).asText();
    final String details = payload.path(ExchangeCatalog.FIELD_DETAILS).asText();
    return Observation.failed(symptom, summary, Map.of("details", details));
  }

  /** Joins the two narration lines: consulted + cohort finding. */
  private String narrationLine(Symptom symptom) {
    return consultedLine(symptom) + " — " + cohortFinding(symptom);
  }

  /**
   * The Diagnosis (⚕ generalist summary) + Assessment (🔬 specialist reasoning) + Mitigation (℞
   * prescriptions) block, as AsciiDoc text. Each reply's assessment is always rendered; its
   * prescription (mitigation) is rendered only when present. Copied from RunbookRenderer.
   */
  private String diagnosisBlock(RemediationPlan plan) {
    final StringBuilder block = new StringBuilder();
    block.append("⚕ Diagnosis: ").append(plan.generalistSummary());
    for (ReferralReply reply : plan.replies()) {
      block
          .append("\n\n🔬 Assessment (")
          .append(reply.assessment().schemaRef().id())
          .append("): ")
          .append(reply.assessment().summary());
      if (reply.hasPrescription()) {
        block
            .append("\n\n℞ Mitigation (")
            .append(reply.prescription().get().programRef().id())
            .append("): ")
            .append(reply.prescription().get().humanHint());
      }
    }
    return block.toString();
  }

  /**
   * Parse the checkpoint payload String with doctor-core's own jackson (no JsonNode crosses the
   * seam).
   */
  private JsonNode parse(String payload) {
    try {
      return mapper.readTree(payload);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("malformed checkpoint payload", e);
    }
  }

  /** Serialize the consultation payload tree back to the String the seam carries. */
  private String serialize(JsonNode node) {
    try {
      return mapper.writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("could not serialize consultation payload", e);
    }
  }

  /**
   * The patient consults: refer the symptom + observation to the routed specialists, collect each
   * specialist's {@link ReferralReply} (always an assessment, optionally a prescription), return a
   * remediation plan carrying the replies.
   */
  @Override
  public RemediationPlan consult(Symptom symptom, Observation observation) {
    final MedicalRecord record = access.record();
    firstLook(record, symptom, observation);
    final List<Specialty> route = routeBySymptom(symptom);
    if (route.isEmpty()) {
      return new RemediationPlan(
          symptom, List.of(), "no specialist routes for symptom " + symptom.id());
    }

    final Referral referral = Referral.of(record.patient(), symptom, observation, record);
    final List<ReferralReply> replies = new ArrayList<>();
    for (Specialist specialist : specialists) {
      if (route.contains(specialist.domain())) {
        replies.add(refer(specialist, referral));
      }
    }

    final long prescribed = replies.stream().filter(ReferralReply::hasPrescription).count();
    final String summary =
        replies.isEmpty()
            ? "consulted " + route + " for " + symptom.id() + "; no specialist replied"
            : replies.size()
                + " reply(ies) for "
                + symptom.id()
                + " from "
                + route
                + "; "
                + prescribed
                + " prescription(s)";
    return new RemediationPlan(symptom, replies, summary);
  }

  /**
   * Run a specialist's two acts and assemble the reply: it ALWAYS assesses, and prescribes only
   * when it has a treatment. The decision sits HERE, in the coordinator — the seam where the
   * efficacy-first gate will later interpose between the assessment and the prescription (consult
   * the history before authorizing a treatment). The specialist provides the two pieces; the
   * Generalist owns the reply shape.
   */
  private static ReferralReply refer(Specialist specialist, Referral referral) {
    final Assessment assessment = specialist.assess(referral);
    return specialist
        .prescribe(referral, assessment)
        .map(prescription -> ReferralReply.prescribing(referral, assessment, prescription))
        .orElseGet(() -> ReferralReply.assessing(referral, assessment));
  }

  /**
   * The follow-up coordination at synthesis: for each visit with a following visit, for each
   * Expectation on that visit whose predicate held at the next visit (the symptom resolved), review
   * the problem with the drift specialist and collect its letters. The ledger is loaded once by the
   * caller (the reconstruction wiring) and passed in — no no-ledger overload.
   *
   * <p>"Resolved-but-unadministered" today is simply "resolved": no engine administers fixes yet,
   * so every resolved expectation is reviewed.
   */
  @Override
  public List<ReferralReply> reviewOpenProblems(MedicalRecord record, InterventionLedger ledger) {
    final List<ReferralReply> letters = new ArrayList<>();
    final List<Visit> visits = record.visits();
    for (int i = 0; i + 1 < visits.size(); i++) {
      final Visit visit = visits.get(i);
      final Visit nextVisit = visits.get(i + 1);
      for (Expectation expectation : visit.expectations()) {
        if (expectation.predicate().heldAt(nextVisit)) {
          final ProblemReview review =
              new ProblemReview(expectation.problem(), expectation, visit, nextVisit, ledger);
          letters.add(driftSpecialist.review(review));
        }
      }
    }
    return letters;
  }

  private void firstLook(MedicalRecord record, Symptom symptom, Observation observation) {
    // record retrieved + available; step-2 reasoning attaches here
  }

  /**
   * Deterministic symptom → domain routing. A readable rules table, not inference: a symptom maps
   * to the domains whose specialists could treat it. Unknown symptoms route nowhere (empty plan).
   */
  private static List<Specialty> routeBySymptom(Symptom symptom) {
    return switch (symptom) {
      case CONNECTION_REFUSED -> List.of(Specialty.SYSTEMD, Specialty.NETWORK);
      case TIMEOUT -> List.of(Specialty.NETWORK);
      // Cluster-readiness symptoms are typed and named in the runbook from Increment D; no
      // specialist treats them yet, so they route to the CLUSTER domain and yield an empty plan
      // (symptom seen, no treatment offered) until a cluster specialist is added.
      case KUBECONFIG_MISSING, CONTROLLER_NOT_READY -> List.of(Specialty.CLUSTER);
      case API_NOT_READY -> List.of(Specialty.CLUSTER, Specialty.NETWORK);
    };
  }
}
