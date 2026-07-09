package io.nxmatic.rke2lab.doctor.internal;

import io.nxmatic.rke2lab.doctor.records.Intervention;
import io.nxmatic.rke2lab.doctor.records.ProblemRef;
import io.nxmatic.rke2lab.doctor.records.Provenance;
import io.nxmatic.rke2lab.doctor.records.RemediationProgramRef;
import io.nxmatic.rke2lab.seed.broker.codec.DocumentCodec;
import io.nxmatic.rke2lab.seed.broker.port.Action;
import io.nxmatic.rke2lab.seed.broker.port.Coordinate;
import io.nxmatic.rke2lab.seed.broker.port.Document;
import io.nxmatic.rke2lab.seed.broker.port.Domain;
import io.nxmatic.rke2lab.seed.broker.port.InterventionRequest;
import io.nxmatic.rke2lab.seed.broker.port.ReadinessVerdict;
import io.nxmatic.rke2lab.seed.broker.port.SeedHandler;
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
 * <p>Published as a {@link SeedHandler} serving {@code intervention} with NO references, so SCR
 * activates it on its own — the CLI sows an intervention-request through the broker without
 * admitting a patient or publishing an EHR/ledger. The Document payload is decoded/encoded through
 * the {@link DocumentCodec} (this realm's own jackson copy); no jackson type crosses the seam.
 */
@Component(service = SeedHandler.class)
public final class DefaultInterventionIntake implements SeedHandler {

  private final DocumentCodec codec = new DocumentCodec();

  @Override
  public Coordinate serves() {
    return Coordinate.INTERVENTION;
  }

  @Override
  public Document handle(Document rawFacts) {
    final InterventionRequest request = codec.decode(rawFacts, InterventionRequest.class);

    if (isBlank(request.problem())) {
      return error("missing problem");
    }
    if (isBlank(request.what())) {
      return error("missing what");
    }

    final Optional<ProblemRef> problem = ProblemRef.parse(request.problem());
    if (problem.isEmpty()) {
      return error("unknown problem reference: " + request.problem());
    }

    final Provenance provenance;
    if (request.provenance().isEmpty()) {
      provenance = Provenance.OPERATOR_MANUAL;
    } else {
      final Optional<Provenance> parsed = Provenance.parse(request.provenance().get());
      if (parsed.isEmpty()) {
        return error("unknown provenance: " + request.provenance().get());
      }
      provenance = parsed.get();
    }

    final Optional<RemediationProgramRef> prescriptionRef;
    if (request.prescriptionRef().isEmpty()) {
      prescriptionRef = Optional.empty();
    } else {
      final Optional<RemediationProgramRef> parsed =
          RemediationProgramRef.parse(request.prescriptionRef().get());
      if (parsed.isEmpty()) {
        return error("unknown prescription-ref: " + request.prescriptionRef().get());
      }
      prescriptionRef = Optional.of(parsed.get());
    }

    final Intervention intervention =
        new Intervention(
            provenance, request.when(), request.what(), problem.get(), prescriptionRef, Map.of());
    return InterventionDocuments.of(intervention);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private Document error(String reason) {
    return new Document(
        Domain.DOCTOR.slug(),
        Coordinate.READINESS_VERDICT.slug(),
        codec.encode(new ReadinessVerdict(Action.STOP, reason)));
  }
}
