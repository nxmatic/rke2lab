package io.nxmatic.rke2lab.doctor.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nxmatic.rke2lab.doctor.port.InterventionIntake;
import io.nxmatic.rke2lab.doctor.records.Intervention;
import io.nxmatic.rke2lab.doctor.records.ProblemRef;
import io.nxmatic.rke2lab.doctor.records.Provenance;
import io.nxmatic.rke2lab.doctor.records.RemediationProgramRef;
import io.nxmatic.rke2lab.world.gateway.codec.DocumentCodec;
import io.nxmatic.rke2lab.world.gateway.port.Action;
import io.nxmatic.rke2lab.world.gateway.port.Coordinate;
import io.nxmatic.rke2lab.world.gateway.port.Document;
import io.nxmatic.rke2lab.world.gateway.port.Domain;
import io.nxmatic.rke2lab.world.gateway.port.ReadinessVerdict;
import io.nxmatic.rke2lab.world.gateway.port.WorldGatewayCatalog;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import org.osgi.service.component.annotations.Component;

/**
 * The OSGi-side intervention canonicalizer: it owns the intervention schema the host no longer
 * holds. Given an {@code intervention-request} Document (the operator's raw argv strings), it
 * parses each reference with the doctor vocabulary ({@link ProblemRef}, {@link Provenance}, {@link
 * RemediationProgramRef}), builds the {@link Intervention}, and returns the canonical {@code
 * intervention} Document carrying {@link Intervention#toOutputMap}. An unparseable reference yields
 * an error {@code readiness-verdict} Document ({@code action=stop} + {@code reason}) rather than a
 * thrown exception across the seam — the doctor record types never leave this method.
 *
 * <p>Published as the {@link InterventionIntake} seam with NO references, so SCR activates it on
 * its own — the CLI awaits it without admitting a patient or publishing an EHR/ledger. The Document
 * payload is parsed and serialized with this bundle's OWN jackson; no jackson type crosses the
 * seam.
 */
@Component(service = InterventionIntake.class)
public final class DefaultInterventionIntake implements InterventionIntake {

  private final ObjectMapper mapper = new ObjectMapper();
  private final DocumentCodec codec = new DocumentCodec();

  @Override
  public Document canonicalize(Document rawFacts) {
    final JsonNode payload = parse(rawFacts.payload());

    final String problemArg = text(payload, WorldGatewayCatalog.FIELD_PROBLEM);
    final String what = text(payload, WorldGatewayCatalog.FIELD_WHAT);
    final String provenanceArg = text(payload, WorldGatewayCatalog.FIELD_PROVENANCE);
    final String prescriptionArg = text(payload, WorldGatewayCatalog.FIELD_PRESCRIPTION_REF);
    final String whenArg = text(payload, WorldGatewayCatalog.FIELD_WHEN);

    if (problemArg == null) {
      return error("missing problem");
    }
    if (what == null) {
      return error("missing what");
    }

    final Optional<ProblemRef> problem = ProblemRef.parse(problemArg);
    if (problem.isEmpty()) {
      return error("unknown problem reference: " + problemArg);
    }

    final Provenance provenance;
    if (provenanceArg == null) {
      provenance = Provenance.OPERATOR_MANUAL;
    } else {
      final Optional<Provenance> parsed = Provenance.parse(provenanceArg);
      if (parsed.isEmpty()) {
        return error("unknown provenance: " + provenanceArg);
      }
      provenance = parsed.get();
    }

    final Optional<RemediationProgramRef> prescriptionRef;
    if (prescriptionArg == null) {
      prescriptionRef = Optional.empty();
    } else {
      final Optional<RemediationProgramRef> parsed = RemediationProgramRef.parse(prescriptionArg);
      if (parsed.isEmpty()) {
        return error("unknown prescription-ref: " + prescriptionArg);
      }
      prescriptionRef = Optional.of(parsed.get());
    }

    final Instant when;
    try {
      when = Instant.parse(whenArg);
    } catch (DateTimeParseException e) {
      return error("invalid when (expected ISO-8601 instant): " + whenArg);
    }

    final Intervention intervention =
        new Intervention(provenance, when, what, problem.get(), prescriptionRef, Map.of());
    return InterventionDocuments.of(intervention);
  }

  private JsonNode parse(String payload) {
    try {
      return mapper.readTree(payload);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("malformed intervention-request payload", e);
    }
  }

  /** A present, non-blank text field, else null. */
  private static String text(JsonNode payload, String field) {
    if (!payload.hasNonNull(field)) {
      return null;
    }
    final String value = payload.get(field).asText();
    return value.isBlank() ? null : value;
  }

  private Document error(String reason) {
    return new Document(
        Domain.DOCTOR.slug(),
        Coordinate.READINESS_VERDICT.slug(),
        codec.encode(new ReadinessVerdict(Action.STOP, reason)));
  }
}
