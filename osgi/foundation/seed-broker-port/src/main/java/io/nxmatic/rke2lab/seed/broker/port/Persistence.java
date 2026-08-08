package io.nxmatic.rke2lab.seed.broker.port;

/**
 * The persistence tier a scion declares when it files a harvest — orthogonal to {@link Sensitivity}
 * (sealing is about secrecy at rest, this is about lifetime). The default is {@link #DURABLE}:
 * every harvest is drained to the durable backend at the run boundary and conserved across runs
 * (the ledger). {@link #TRANSIENT} is the within-run bus: the value rides the transactional overlay
 * (read-your-writes) and descends to sown sub-scions (the inheritance), but is EVICTED at the drain
 * — it never reaches the durable backend, so it never appears in the stack state.
 *
 * <p>It exists for a fact that is PRODUCED and CONSUMED within one run and must not be conserved —
 * typically one whose truth evaporates the moment the run acts on it (the cold/warm growth
 * condition read at grow entry: once the grow starts the instance, the prior state is no longer
 * observable, so the fact is frozen on the transient bus for the rest of the run and discarded at
 * commit). Only the transactional cellar honours it; a plain durable backend has no drain to skip,
 * so it treats every store as {@link #DURABLE}. See docs/architecture/osgi/seed-broker-spec.adoc (§
 * cellar-transactional).
 */
public enum Persistence {

  /** Drained to the durable backend at the run boundary — conserved across runs (the default). */
  DURABLE,

  /**
   * The within-run bus — readable this run (overlay + inheritance) but evicted at the drain, so it
   * never reaches the durable backend nor the stack state.
   */
  TRANSIENT
}
