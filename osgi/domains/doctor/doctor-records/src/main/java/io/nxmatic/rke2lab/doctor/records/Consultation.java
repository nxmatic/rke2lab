package io.nxmatic.rke2lab.doctor.records;

import io.nxmatic.rke2lab.seed.broker.port.Rootstock;
import io.nxmatic.rke2lab.seed.broker.port.Scion;
import io.nxmatic.rke2lab.seed.broker.port.SeedContract;
import java.util.List;
import java.util.Map;

/**
 * The wire contract for the {@code consultation} {@code SeedEnvelope}: what the doctor produced
 * from a checkpoint consult. The host reads only the flat fields ({@code scenarioId} to join the
 * runbook scenario, {@code narration} to log, {@code diagnosisAdoc} to inject into the runbook); it
 * carries {@code consultationReport} and {@code expectations} OPAQUELY — they are a doctor→doctor
 * payload the host files verbatim as grafts, never interpreting them.
 *
 * <p>Those two are therefore modelled as open slots ({@link Map} / {@link List}): the contract is
 * "this slot is a nested structure", its inner shape (the {@code ConsultationReport} / {@code
 * Expectation} record graph) owned OSGi-side, where {@code SeedCodec.fromMap} decodes it. Each
 * realm maps this record ↔ {@code String} via {@code SeedCodec}.
 *
 * <p>Both are marked {@link Scion}: they are the named sub-trees the write frontier files verbatim
 * (and the read frontier collects back). {@code scenarioId} is the {@link Rootstock}: the identity
 * of the receiver the scions graft onto (the checkpoint the report belongs to). The scion's name is
 * the component's own name, so doctor names no storage slot — it declares WHICH components are
 * scions and WHICH is the rootstock, and the frontier learns them by asking the broker (the {@code
 * SplitCoordinate} reflector), never by holding a constant.
 */
@SeedContract("consultation")
public record Consultation(
    @Rootstock String scenarioId,
    String narration,
    String diagnosisAdoc,
    @Scion("fruit") Map<String, Object> consultationReport,
    @Scion("sowing") List<Object> expectations) {

  public Consultation {
    consultationReport = consultationReport == null ? Map.of() : Map.copyOf(consultationReport);
    expectations = expectations == null ? List.of() : List.copyOf(expectations);
  }
}
