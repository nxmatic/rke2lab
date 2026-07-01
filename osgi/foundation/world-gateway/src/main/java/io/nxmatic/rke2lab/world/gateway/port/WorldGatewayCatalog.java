package io.nxmatic.rke2lab.world.gateway.port;

/**
 * The single source of truth for the gateway's payload field keys — the schema. Closed value
 * domains (domain, coordinate, action, symptom-kind) are now typed enums in this package: {@link
 * Domain}, {@link Coordinate}, {@link Action}, {@link SymptomKind}. Call sites reference these
 * enums and their constants, never literals, so a mismatch cannot drift silently (the {@code
 * clusterApi}-bug discipline). Build-time schemas (a later increment) key on the field names here.
 */
public final class WorldGatewayCatalog {

  /** Consultation payload: the scenario id (e.g. the systemd-adapter checkpoint slug). */
  public static final String FIELD_SCENARIO_ID = "scenarioId";

  /** Consultation payload: the symptom's plain-text narration. */
  public static final String FIELD_NARRATION = "narration";

  /** Consultation payload: the specialists' rendered AsciiDoc diagnosis. */
  public static final String FIELD_DIAGNOSIS_ADOC = "diagnosisAdoc";

  /**
   * Visit payload: the monotonically increasing stack version of the history entry the visit was
   * read from. The host READ journal stamps it; OSGi folds it back into the rebuilt {@code Visit}.
   */
  public static final String FIELD_VERSION = "version";

  /**
   * Consultation payload + Pulumi egress key: the structured consultation report sub-tree
   * (checkpointId + observations + plan). The host copies it opaquely from the consultation
   * Document to this output key; reconstruction reads it back by the same name. Must equal {@code
   * ConsultationReport.OUTPUT_KEY} (the doctor-side producer) — the shared seam name, so neither
   * side hardcodes the literal.
   */
  public static final String FIELD_CONSULTATION_REPORT = "consultationReport";

  /**
   * Consultation payload + Pulumi egress key: the structured expectations sub-tree (what a
   * prescribing consultation predicts will resolve by the next visit). Copied opaquely to this
   * output key; reconstruction reads it back by the same name. Must equal {@code
   * Expectation.OUTPUT_KEY}.
   */
  public static final String FIELD_EXPECTATIONS = "expectations";

  /** Visit payload: the ISO-8601 instant a history entry was recorded. */
  public static final String FIELD_WHEN = "when";

  private WorldGatewayCatalog() {}
}
