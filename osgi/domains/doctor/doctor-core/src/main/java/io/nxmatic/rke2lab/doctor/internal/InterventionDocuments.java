package io.nxmatic.rke2lab.doctor.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nxmatic.rke2lab.doctor.records.Intervention;
import io.nxmatic.rke2lab.world.gateway.port.Coordinate;
import io.nxmatic.rke2lab.world.gateway.port.Document;
import io.nxmatic.rke2lab.world.gateway.port.Domain;

/**
 * Wraps a doctor-side {@link Intervention} into the canonical {@code intervention} {@link Document}
 * the write seam carries — the one place that serializes {@link Intervention#toOutputMap} with
 * doctor-core's OWN jackson, shared by both producers ({@code InterventionIntake} ingress, {@code
 * DriftSpecialist} inference) so the canonical shape is written once. The {@link Intervention}
 * stays OSGi-internal; only the {@link Document} crosses the seam.
 */
final class InterventionDocuments {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private InterventionDocuments() {}

  /** The canonical {@code intervention} Document carrying {@code intervention.toOutputMap()}. */
  static Document of(Intervention intervention) {
    try {
      return new Document(
          Domain.DOCTOR.slug(),
          Coordinate.INTERVENTION.slug(),
          MAPPER.writeValueAsString(intervention.toOutputMap()));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("could not serialize intervention payload", e);
    }
  }
}
