package io.nxmatic.rke2lab.doctor.records;

import io.nxmatic.rke2lab.seed.broker.port.SeedContract;
import java.util.List;
import java.util.Map;

/**
 * The wire contract for the {@code consultation} {@link Document}: what the doctor produced from a
 * checkpoint consult. The host reads only the flat fields ({@code scenarioId} to join the runbook
 * scenario, {@code narration} to log, {@code diagnosisAdoc} to inject into the runbook); it carries
 * {@code consultationReport} and {@code expectations} OPAQUELY — they are a doctor→doctor payload
 * the host copies verbatim into its Pulumi outputs, never interpreting them.
 *
 * <p>Those two are therefore modelled as open slots ({@link Map} / {@link List}): the contract is
 * "this slot is a nested structure", its inner shape (the {@code ConsultationReport} / {@code
 * Expectation} record graph) owned OSGi-side, where {@code SeedCodec.fromMap} decodes it. Each
 * realm maps this record ↔ {@code String} via {@code SeedCodec}.
 */
@SeedContract("consultation")
public record Consultation(
    String scenarioId,
    String narration,
    String diagnosisAdoc,
    Map<String, Object> consultationReport,
    List<Object> expectations) {

  public Consultation {
    consultationReport = consultationReport == null ? Map.of() : Map.copyOf(consultationReport);
    expectations = expectations == null ? List.of() : List.copyOf(expectations);
  }
}
