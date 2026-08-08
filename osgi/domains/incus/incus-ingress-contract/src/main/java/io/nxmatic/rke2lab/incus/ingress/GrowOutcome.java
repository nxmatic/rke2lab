package io.nxmatic.rke2lab.incus.ingress;

import io.nxmatic.rke2lab.seed.broker.port.SeedContract;

/**
 * The produced fact of a grow — what the host observed at grow entry, filed on the run's TRANSIENT
 * cellar bus so the rest of the run can read it without re-observing (which is impossible once the
 * grow acts). Its sole field today is the {@link Growth} condition; it is a record so a later grow
 * fact rides alongside without a new coordinate.
 *
 * <p>The scion-projects / host-actualises twin of {@link InstanceGrowPlan} runs the other way: the
 * host PRODUCES this (it alone reads the live stack), a consumer READS it. It is stored {@link
 * io.nxmatic.rke2lab.seed.broker.port.Persistence#TRANSIENT} — the within-run bus, evicted at the
 * drain — because the fact must not be conserved (its truth is a point-in-time observation, not a
 * harvest). {@link SeedContract} binds it to {@link IncusGrowCoordinate#GROW_OUTCOME} for the
 * codec's decode guard; like {@link InstanceGrowPlan} it is held in both realms (the intra-domain
 * dual-realm exception).
 */
@SeedContract("grow-outcome")
public record GrowOutcome(Growth growth) {}
