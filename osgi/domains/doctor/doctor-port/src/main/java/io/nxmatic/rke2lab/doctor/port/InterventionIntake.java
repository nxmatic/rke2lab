package io.nxmatic.rke2lab.doctor.port;

import io.nxmatic.rke2lab.world.gateway.port.Document;

/**
 * The ingress canonicalization seam: the doctor turns a host-built {@code intervention-request}
 * Document (raw argv strings — problem, what, provenance, prescriptionRef, when) into a canonical
 * {@code intervention} Document the ledger persists. The intervention schema lives entirely
 * OSGi-side; the host holds no doctor type.
 *
 * <p>A standalone seam, NOT a verb on {@link ConsultingService}: canonicalization needs no patient,
 * EHR, or ledger, so its {@code @Component} activates without the host publishing any of those
 * references — the CLI {@code awaitService(InterventionIntake.class)} boot stays light (no patient
 * admission). Only this interface and {@link Document} cross; no doctor record, no jackson type.
 */
public interface InterventionIntake {

  /**
   * Canonicalize raw intervention facts into the persistable intervention Document. The raw facts
   * are an {@code intervention-request} Document whose payload carries the operator's argv strings;
   * the result is an {@code intervention} Document whose payload is the flat {@code
   * Intervention.toOutputMap} shape. On an unparseable reference (problem / provenance /
   * prescription-ref), the result is an error verdict Document (a {@code reason}-bearing payload)
   * rather than a thrown exception across the seam — the caller maps it to a non-zero exit.
   *
   * @param rawFacts the {@code intervention-request} Document
   * @return the canonical {@code intervention} Document, or an error verdict Document
   */
  Document canonicalize(Document rawFacts);
}
