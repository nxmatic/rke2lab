package io.nxmatic.rke2lab.incus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.incus.contract.HostDriftEntry;
import io.nxmatic.rke2lab.incus.contract.HostLiveEntry;
import io.nxmatic.rke2lab.incus.contract.HostStagingEntry;
import io.nxmatic.rke2lab.incus.contract.HostStagingEntry.Provenance;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins I6a-B2: the HEAD is a last-wins fold of the cellar timeline. The pivot the grow needs
 * ({@code liveSyncedFrom}) is the last {@code host-live} entry; stagings and drifts fold per path.
 */
class HostTreeHeadTest {

  private static final SeedCodec CODEC = new SeedCodec();

  private static SeedEnvelope staging(String root, String hash) {
    return new SeedEnvelope(
        "manifests",
        "host-staging",
        CODEC.encode(
            HostStagingEntry.of(root, Map.of("f", hash), new Provenance("abc123", false))));
  }

  private static SeedEnvelope live(String syncedFrom) {
    return new SeedEnvelope("host", "host-live", CODEC.encode(HostLiveEntry.of(syncedFrom)));
  }

  private static SeedEnvelope drift(String driftRoot, String evicted) {
    return new SeedEnvelope(
        "host", "host-drift", CODEC.encode(HostDriftEntry.of(driftRoot, evicted)));
  }

  @Test
  void an_empty_timeline_folds_to_an_empty_head() {
    final HostTreeHead head = HostTreeHead.fold(List.of(), CODEC);
    assertTrue(head.liveSyncedFrom().isEmpty(), "no live entry → no pivot");
    assertTrue(head.stagings().isEmpty());
    assertTrue(head.drifts().isEmpty());
  }

  @Test
  void the_last_live_entry_wins_as_the_pivot() {
    final HostTreeHead head =
        HostTreeHead.fold(
            List.of(
                staging("/host.0.staging.d", "a"),
                live("/host.0.staging.d"),
                live("/host.2.staging.d")),
            CODEC);
    assertEquals("/host.2.staging.d", head.liveSyncedFrom().orElseThrow(), "last live entry wins");
  }

  @Test
  void a_recycled_staging_path_supersedes_the_old_entry() {
    // The same path re-materialised (bounded rotation) — the later checksum must win.
    final HostTreeHead head =
        HostTreeHead.fold(
            List.of(staging("/host.0.staging.d", "old"), staging("/host.0.staging.d", "new")),
            CODEC);
    assertEquals(1, head.stagings().size(), "one entry per path");
    assertEquals("new", head.stagings().get("/host.0.staging.d").checksums().get("f"), "last-wins");
  }

  @Test
  void stagings_drifts_and_live_fold_side_by_side() {
    final HostTreeHead head =
        HostTreeHead.fold(
            List.of(
                staging("/host.0.staging.d", "a"),
                staging("/host.1.staging.d", "b"),
                live("/host.1.staging.d"),
                drift("/host.0.drift", "/host.0.staging.d")),
            CODEC);
    assertEquals("/host.1.staging.d", head.liveSyncedFrom().orElseThrow());
    assertEquals(2, head.stagings().size());
    assertEquals(1, head.drifts().size());
    assertEquals("/host.0.staging.d", head.drifts().get("/host.0.drift").evictedSyncedFrom());
  }

  @Test
  void unrelated_envelopes_are_ignored() {
    final HostTreeHead head =
        HostTreeHead.fold(
            List.of(
                new SeedEnvelope("bbox", "some-harvest", "{}"), staging("/host.0.staging.d", "a")),
            CODEC);
    assertEquals(1, head.stagings().size(), "a non-host-tree envelope is not this fold's concern");
    assertFalse(head.stagings().isEmpty());
  }
}
