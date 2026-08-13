package io.seedmatic.rke2lab.incus.ingress;

/**
 * The cold/warm condition of a grow — a fact READ at grow entry, before the grow actualises the
 * instance. {@link #WARM}: the instance was already present and running in the prior stack state (a
 * re-run against a live master). {@link #COLD}: it was absent, or present but not running (a fresh
 * bring-up, or one that must boot). The truth is only observable at grow ENTRY — once the grow sets
 * the instance running, the prior state is gone — so it is frozen on the run's TRANSIENT cellar bus
 * (§ seed-broker-spec, the transient tier) and never conserved.
 *
 * <p>It conditions the readiness budget, not the domain logic: WARM ⇒ a short fail-fast deadline (a
 * live master should answer at once), COLD ⇒ the patient deadline that absorbs a boot.
 */
public enum Growth {
  WARM,
  COLD
}
