package io.nxmatic.rke2lab.doctor.records;

import io.nxmatic.rke2lab.seed.broker.port.SeedContract;
import java.time.Instant;
import java.util.Optional;

/**
 * The wire contract for the {@code intervention-request} {@link Document}: the operator's raw facts
 * the CLI pushes OSGi-side for canonicalization. The references stay RAW strings here — {@code
 * problem}, {@code provenance}, {@code prescriptionRef} are parsed into the doctor vocabulary
 * ({@code ProblemRef}, {@code Provenance}, {@code RemediationProgramRef}) only OSGi-side, which
 * owns that schema; the host never holds a doctor type. {@code provenance} and {@code
 * prescriptionRef} are optional (provenance defaults to operator-manual OSGi-side; prescriptionRef
 * is absent unless engine-driven). The record's components ARE the wire shape; each realm maps it ↔
 * {@code String} with its own jackson via {@code SeedCodec}.
 */
@SeedContract("intervention-request")
public record InterventionRequest(
    String problem,
    String what,
    Optional<String> provenance,
    Optional<String> prescriptionRef,
    Instant when) {

  // Normalize a null Optional to empty — the codec fills an absent wire key with null on the
  // canonical constructor; every wire-record with an Optional field keeps this invariant so an
  // accessor never returns null (the codebase's Optional discipline).
  public InterventionRequest {
    provenance = provenance == null ? Optional.empty() : provenance;
    prescriptionRef = prescriptionRef == null ? Optional.empty() : prescriptionRef;
  }
}
