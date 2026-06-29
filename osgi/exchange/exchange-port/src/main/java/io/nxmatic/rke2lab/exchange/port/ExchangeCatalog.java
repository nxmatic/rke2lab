package io.nxmatic.rke2lab.exchange.port;

/**
 * The single source of truth for the exchange's payload field keys — the schema. Closed value
 * domains (domain, coordinate, action, symptom-kind) are now typed enums in this package: {@link
 * Domain}, {@link Coordinate}, {@link Action}, {@link SymptomKind}. Call sites reference these
 * enums and their constants, never literals, so a mismatch cannot drift silently (the {@code
 * clusterApi}-bug discipline). Build-time schemas (a later increment) key on the field names here.
 */
public final class ExchangeCatalog {

  /** Checkpoint payload: the scenario id (e.g. the systemd-adapter checkpoint slug). */
  public static final String FIELD_SCENARIO_ID = "scenarioId";

  /** Checkpoint payload: whether the checkpoint failed. */
  public static final String FIELD_FAILED = "failed";

  /** Checkpoint payload: the operator's raw severity override for this scenario, or absent. */
  public static final String FIELD_OVERRIDE = "override";

  /** Checkpoint payload: the symptom's plain-text narration. */
  public static final String FIELD_NARRATION = "narration";

  /** Consultation payload: the specialists' rendered AsciiDoc diagnosis. */
  public static final String FIELD_DIAGNOSIS_ADOC = "diagnosisAdoc";

  /**
   * Checkpoint payload: the captured observations, a list of the flat {@code
   * Observation.toOutputMap} shape (status, summary, the symptom slug under its envelope key,
   * details). One for the systemd-adapter checkpoint, N (one per phase) for cluster-readiness — a
   * uniform schema across both consult paths, so OSGi keeps every observation in the reconstructed
   * record (no information lost at the seam). OSGi routes on the first observation carrying a
   * symptom.
   */
  public static final String FIELD_OBSERVATIONS = "observations";

  /** Checkpoint payload: the host's run instant (ISO-8601 string) for expectation timestamps. */
  public static final String FIELD_RECORDED_AT = "recordedAt";

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

  /** Verdict payload: the provisioning action (see {@link Action} for values). */
  public static final String FIELD_ACTION = "action";

  /** Verdict payload: a human-readable reason for the action. */
  public static final String FIELD_REASON = "reason";

  private ExchangeCatalog() {}
}
