package io.nxmatic.rke2lab.seed.broker.port;

import java.util.List;

/**
 * The commissioner's append-only store, addressed by a neutral {@link Parcel} — the seam the host
 * EDGE provides (over the Pulumi backend) and a domain CONSUMES to keep and retrieve its sealed
 * végétaux. It speaks the CONSERVATION register, never gardening: a cellar does not grow anything,
 * it stores what a harvest brought back. A cellar is a NEUTRAL store — it holds any {@link
 * SeedEnvelope} (crop OR tool), never typed by contents; it knows only the parcel (the shelf) and
 * the gesture, mirroring the envelope's opacity (it never opens the payload).
 *
 * <p>This ONE port subsumes the three doctor-named host ports of the pre-vision model ({@code
 * MedicalRecordJournal.historyOf}/{@code cohort}, {@code InterventionJournal.entries}, {@code
 * InterventionLedgerWriter.append}): {@code fetch} is the read (a timeline walk yielding opaque
 * envelopes), {@code store} the append, {@code neighbours} the sibling enumeration. The ledger is
 * just a fixed parcel. Homed in the neutral seam (not a domain {@code -port}) because its FACE is
 * neutral by requirement; the doctor's {@code historyOf(Patient)}-shaped view sits BEHIND it as a
 * projection ({@code Patient ↔ Parcel}). See docs/architecture/osgi/seed-broker-spec.adoc.
 */
public interface Cellar {

  /**
   * Put a végétal away in the parcel's cellar — append-only, never overwriting. The store never
   * reads the payload; it files the sealed envelope under its coordinate.
   */
  void store(Parcel parcel, SeedEnvelope vegetal);

  /**
   * Go get what is stored in the parcel's cellar — the timeline, oldest first, one opaque {@link
   * SeedEnvelope} per readable entry. NOT "harvest" (the field's hand-over gesture): this is
   * retrieval. An absent or empty cellar yields an empty list (a legitimate nothing-here); a
   * present-but-unreadable store degrades to the readable prefix rather than throwing.
   */
  List<SeedEnvelope> fetch(Parcel parcel);

  /**
   * The parcel's neighbouring cellars — the sibling parcels sharing the same soil (the parcel's own
   * first). Sibling enumeration is Layer-1 host knowledge (which stacks exist under the backend);
   * any grant filter is applied by the consumer, not here. With no backend, the neighbourhood is
   * just the parcel itself.
   */
  List<Parcel> neighbours(Parcel parcel);
}
