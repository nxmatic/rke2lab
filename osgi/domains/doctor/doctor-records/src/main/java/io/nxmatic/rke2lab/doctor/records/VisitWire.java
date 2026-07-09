package io.nxmatic.rke2lab.doctor.records;

import io.nxmatic.rke2lab.seed.broker.port.SeedContract;
import java.time.Instant;
import java.util.List;

/**
 * The wire contract for the {@code visit} {@code SeedEnvelope}: one history entry the host READ
 * journal harvested from a patient's stack timeline. {@code version} + {@code when} identify the
 * entry; the two blob lists are the raw consultation-report and expectation grafts the host
 * collected back WITHOUT interpreting them.
 *
 * <p>Both lists are OPAQUE ({@code List} of open objects): they carry the host's graft-collection
 * framing (one inner element per rootstock) that OSGi's {@code MedicalRecordReader} unwraps and
 * folds into a typed {@code Visit} via {@code SeedCodec.fromMap}. The host never parses the blobs;
 * each realm maps this record ↔ {@code String} via {@code SeedCodec}.
 */
@SeedContract("visit")
public record VisitWire(
    int version, Instant when, List<Object> consultationReport, List<Object> expectations) {

  public VisitWire {
    consultationReport = consultationReport == null ? List.of() : List.copyOf(consultationReport);
    expectations = expectations == null ? List.of() : List.copyOf(expectations);
  }
}
