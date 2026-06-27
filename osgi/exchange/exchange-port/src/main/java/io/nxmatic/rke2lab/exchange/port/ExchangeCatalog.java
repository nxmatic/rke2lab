package io.nxmatic.rke2lab.exchange.port;

/**
 * The single source of truth for the exchange's payload field keys — the schema. Closed value
 * domains (coordinates, actions, symptom kinds) are now typed enums in this package. Call sites
 * reference these constants, never literals, so a mismatch cannot drift silently (the {@code
 * clusterApi}-bug discipline). Build-time schemas (a later increment) key on the field names here.
 */
public final class ExchangeCatalog {

  /** The doctor domain owns the readiness vocabulary. */
  public static final String DOMAIN_DOCTOR = "doctor";

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

  /** Checkpoint payload: the symptom kind discriminator. */
  public static final String FIELD_SYMPTOM_KIND = "symptomKind";

  /** Checkpoint payload: the symptom's summary text. */
  public static final String FIELD_SUMMARY = "summary";

  /** Checkpoint payload: the symptom's detail text. */
  public static final String FIELD_DETAILS = "details";

  /** Verdict payload: the provisioning action (see {@link Action} for values). */
  public static final String FIELD_ACTION = "action";

  /** Verdict payload: a human-readable reason for the action. */
  public static final String FIELD_REASON = "reason";

  private ExchangeCatalog() {}
}
