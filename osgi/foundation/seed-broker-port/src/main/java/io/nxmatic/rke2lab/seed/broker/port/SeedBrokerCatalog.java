package io.nxmatic.rke2lab.seed.broker.port;

/**
 * The two shared PULUMI OUTPUT KEYS under which the doctor's opaque sub-trees are carried through
 * the host's Pulumi state — NOT gateway wire fields (every coordinate's wire shape is now a typed
 * wire-record whose components ARE the schema, projected by {@code SCHEMA_CONCORD}). These two
 * names survive because they name a host-internal transport slot on BOTH sides of a round-trip: a
 * resource registers the blob under the key, and reconstruction reads it back by the same name.
 * Each must equal its doctor-side producer constant ({@code ConsultationReport.OUTPUT_KEY} / {@code
 * Expectation.OUTPUT_KEY}) so neither side hardcodes the literal.
 */
public final class SeedBrokerCatalog {

  /**
   * Pulumi output key: the structured consultation-report sub-tree (checkpointId + observations +
   * plan). The host copies it opaquely from the {@link Consultation} to this output key;
   * reconstruction reads it back by the same name. Must equal {@code
   * ConsultationReport.OUTPUT_KEY}.
   */
  public static final String FIELD_CONSULTATION_REPORT = "consultationReport";

  /**
   * Pulumi output key: the structured expectations sub-tree (what a prescribing consultation
   * predicts will resolve by the next visit). Copied opaquely to this output key; reconstruction
   * reads it back by the same name. Must equal {@code Expectation.OUTPUT_KEY}.
   */
  public static final String FIELD_EXPECTATIONS = "expectations";

  private SeedBrokerCatalog() {}
}
