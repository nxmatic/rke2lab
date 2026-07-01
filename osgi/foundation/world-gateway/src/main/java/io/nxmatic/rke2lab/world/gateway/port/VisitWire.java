package io.nxmatic.rke2lab.world.gateway.port;

import java.time.Instant;
import java.util.List;

/**
 * The wire contract for the {@code visit} {@link Document}: one history entry the host READ journal
 * harvested from a patient's stack timeline. {@code version} + {@code when} identify the entry; the
 * two blob lists are the raw consultation-report and expectation Pulumi outputs the host copied
 * WITHOUT interpreting them.
 *
 * <p>Both lists are OPAQUE ({@code List} of open objects, for which the {@code SCHEMA_CONCORD}
 * projector emits an open {@code array}): they carry the {@code outputsNamed} transport framing
 * (one inner element per resource) that OSGi's {@code MedicalRecordReader} unwraps and folds into a
 * typed {@code Visit} via {@code ConsultationReportReader} / {@code ExpectationReader}. The host
 * never parses the blobs; each realm maps this record ↔ {@code String} via {@code DocumentCodec}.
 */
@DocumentContract(Coordinate.VISIT)
public record VisitWire(
    int version, Instant when, List<Object> consultationReport, List<Object> expectations) {

  public VisitWire {
    consultationReport = consultationReport == null ? List.of() : List.copyOf(consultationReport);
    expectations = expectations == null ? List.of() : List.copyOf(expectations);
  }
}
