package io.nxmatic.rke2lab.doctor.internal;

import io.nxmatic.rke2lab.doctor.records.Intervention;
import io.nxmatic.rke2lab.world.gateway.codec.DocumentCodec;
import io.nxmatic.rke2lab.world.gateway.port.Coordinate;
import io.nxmatic.rke2lab.world.gateway.port.Document;
import io.nxmatic.rke2lab.world.gateway.port.Domain;
import io.nxmatic.rke2lab.world.gateway.port.InterventionWire;

/**
 * Wraps a doctor-side {@link Intervention} into the canonical {@code intervention} {@link Document}
 * the write seam carries — the one place that projects an {@link Intervention} onto its {@link
 * InterventionWire} and encodes it with the realm's {@link DocumentCodec}, shared by both producers
 * (the intervention ingress SeedHandler, {@code DriftSpecialist} inference) so the canonical shape
 * is written once. The {@link Intervention} (and the doctor vocabulary its refs carry) stays
 * OSGi-internal; only the flat {@link Document} crosses the seam.
 */
final class InterventionDocuments {

  private static final DocumentCodec CODEC = new DocumentCodec();

  private InterventionDocuments() {}

  /**
   * The canonical {@code intervention} Document carrying this intervention's {@link
   * InterventionWire}.
   */
  static Document of(Intervention intervention) {
    final InterventionWire wire =
        new InterventionWire(
            intervention.provenance().id(),
            intervention.when(),
            intervention.what(),
            intervention.problem().toRef(),
            intervention.prescriptionRef().map(ref -> ref.id()),
            intervention.details());
    return new Document(Domain.DOCTOR.slug(), Coordinate.INTERVENTION.slug(), CODEC.encode(wire));
  }
}
