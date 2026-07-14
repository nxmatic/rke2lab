package io.nxmatic.rke2lab.manifests.contract;

import io.nxmatic.rke2lab.seed.broker.port.SeedContract;

/**
 * The DRIFT entry of the host-manifest family — NOT a {@code --backup-dir} of evicted bytes (the
 * rollback re-syncs a still-present immutable staging, never the evicted bytes) but the record of a
 * DRIFT the live had accumulated before a sync overwrote it (see
 * docs/architecture/osgi/host-cellar-realisation-spec.adoc § The two deltas). The HOST writes it at
 * the grow (I6d), for a {@code host.drift.N} rotation position: {@code driftRoot} is the drift
 * REPORT's PATH on the FS (the {@code host.drift.N} tree carrying the {@code ReportModel},
 * adoc+json), and {@code evictedSyncedFrom} names the staging PATH the live had drifted from (the
 * {@code live.syncedFrom} the sync replaced). IMMUTABLE until its slot is recycled by the bounded
 * rotation.
 *
 * <p>{@link SeedContract} binds it to the {@code host-drift} coordinate. The reader-side fold is
 * {@link HostTreeHead}; the twin natures are {@link HostStagingEntry} and {@link HostLiveEntry}.
 */
@SeedContract("host-drift")
public record HostDriftEntry(String driftRoot, String evictedSyncedFrom) {

  public static HostDriftEntry of(String driftRoot, String evictedSyncedFrom) {
    return new HostDriftEntry(driftRoot, evictedSyncedFrom);
  }
}
