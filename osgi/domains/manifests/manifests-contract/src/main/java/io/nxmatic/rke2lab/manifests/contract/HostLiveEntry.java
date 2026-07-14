package io.nxmatic.rke2lab.manifests.contract;

import io.nxmatic.rke2lab.seed.broker.port.SeedContract;

/**
 * The LIVE entry of the host-manifest family — the SOLE mutable entry (see
 * docs/architecture/osgi/host-cellar-realisation-spec.adoc § The host tree the instance mounts). It
 * names the {@code syncedFrom} staging PATH the physical {@code host.live} currently mirrors — the
 * PIVOT both deltas compare against (NOT the arithmetic N-1: interleaved previews produce stagings
 * the live never adopted). It is the same path a {@link HostStagingEntry} published (the host chose
 * it as the SOIL), so the two agree by construction. The HOST writes it — and ONLY it — appended
 * post-sync-success at the grow (I6d), so the manifest never lies about the FS; PREVIEW-vs-PROMOTED
 * is deduced from it (a staging is preview-only while no live entry names it).
 *
 * <p>{@link SeedContract} binds it to the {@code host-live} coordinate. The reader-side fold is
 * {@link HostTreeHead}, whose {@code liveSyncedFrom} is the last such entry in the timeline.
 */
@SeedContract("host-live")
public record HostLiveEntry(String syncedFrom) {

  public static HostLiveEntry of(String syncedFrom) {
    return new HostLiveEntry(syncedFrom);
  }
}
