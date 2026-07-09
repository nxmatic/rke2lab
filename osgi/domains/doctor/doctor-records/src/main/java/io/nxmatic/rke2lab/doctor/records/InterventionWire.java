package io.nxmatic.rke2lab.doctor.records;

import io.nxmatic.rke2lab.seed.broker.port.SeedContract;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * The wire contract for the canonical {@code intervention} {@link Document}: ONE intervention — who
 * changed the world ({@code provenance}), {@code when}, {@code what} was done, the {@code problem}
 * it addresses, an optional engine {@code prescriptionRef}, and an open {@code details} bag for
 * producer-specific context. The references stay RAW strings on the wire (the OSGi side owns the
 * doctor vocabulary — {@code Provenance}, {@code ProblemRef}, {@code RemediationProgramRef} — and
 * never lets it cross); the host only transports the {@code String} payload.
 *
 * <p>ONE intervention, not a list: a ledger history entry records exactly one intervention, and the
 * host journal emits one {@code intervention} Document per entry. (The former {@code
 * {interventions:[…]}} envelope was Pulumi's array-valued {@code outputsNamed} framing leaking into
 * the payload — transport, not contract.) The record's components ARE the wire shape; each realm
 * maps it ↔ {@code String} via {@code SeedCodec}.
 */
@SeedContract("intervention")
public record InterventionWire(
    String provenance,
    Instant when,
    String what,
    String problem,
    Optional<String> prescriptionRef,
    Map<String, Object> details) {

  public InterventionWire {
    prescriptionRef = prescriptionRef == null ? Optional.empty() : prescriptionRef;
    details = details == null ? Map.of() : Map.copyOf(details);
  }
}
