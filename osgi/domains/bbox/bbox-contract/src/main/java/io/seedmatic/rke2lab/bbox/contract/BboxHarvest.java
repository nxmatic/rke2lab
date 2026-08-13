package io.seedmatic.rke2lab.bbox.contract;

import io.seedmatic.rke2lab.seed.broker.port.SeedContract;
import java.util.List;

/**
 * The bbox scion's HARVEST — the reservation-reconciliation summary it stores at the cellar, the
 * home of what the former host {@code BboxReservationsResource} exposed as its Pulumi outputs
 * ({@code desiredCount}, one count per {@link BboxAction}). On the Pulumi realisation the scion's
 * {@code Cellar.store} of this record PRODUCES the bbox resource — the dissolution of the former
 * {@code ResourceManager} into the scion's own store (see
 * docs/architecture/osgi/host-cellar-realisation-spec.adoc § every-scion-contributes).
 *
 * <p>No {@code dryRun} field: the mode lives at the reconciler frontier, and the cellar consults
 * the RunGate itself to route conserve vs pre-reserve — the harvest stays mode-blind, its {@code
 * WOULD_*} counts already telling a surveyed run from a cultivated one.
 *
 * <p>It carries no {@code @Scion}/{@code @Rootstock}: bbox reaps a single conservable part (the
 * summary) with no forward {@code sowing} to separate, so the whole record IS the fruit — stored
 * verbatim under its coordinate, no split. A scion whose harvest mixes fruit and sowing (the
 * doctor's consultation) marks its components and contributes a split reflector; bbox does not need
 * one. {@link SeedContract} binds it to the {@code bbox-reservations} coordinate for the codec's
 * decode guard; the wire-record type never crosses the seam, only the serialized payload.
 */
@SeedContract("bbox-reservations")
public record BboxHarvest(
    int desiredCount,
    int createdCount,
    int updatedCount,
    int matchingCount,
    int wouldCreateCount,
    int wouldUpdateCount,
    int failedCount) {

  /** Fold a run's row outcomes into the summary — the counts per {@link BboxAction}. */
  public static BboxHarvest of(List<BboxRowOutcome> outcomes) {
    return new BboxHarvest(
        outcomes.size(),
        count(outcomes, BboxAction.CREATED),
        count(outcomes, BboxAction.UPDATED),
        count(outcomes, BboxAction.MATCHING),
        count(outcomes, BboxAction.WOULD_CREATE),
        count(outcomes, BboxAction.WOULD_UPDATE),
        count(outcomes, BboxAction.FAILED));
  }

  private static int count(List<BboxRowOutcome> outcomes, BboxAction action) {
    return (int) outcomes.stream().filter(o -> o.action() == action).count();
  }
}
