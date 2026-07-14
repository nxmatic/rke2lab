package io.nxmatic.rke2lab.controlplane;

import io.nxmatic.rke2lab.manifests.contract.HostDriftEntry;
import io.nxmatic.rke2lab.manifests.contract.HostLiveEntry;
import io.nxmatic.rke2lab.manifests.contract.HostStagingEntry;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The HEAD of the host-tree manifest — the current state FOLDED from the cellar's append-only
 * timeline (see docs/architecture/osgi/host-cellar-realisation-spec.adoc § the two reads). It is
 * NOT a stored document: the manifest is ENTRIES PER REPLICA (each an append-only {@code
 * Cellar.store}), and the logical head is their fold at read — last-wins per key. This is a
 * convenience fold on the reader side (the doctor folds its timeline the same way), not a new port
 * verb; the cellar stays neutral at three verbs.
 *
 * <p>The host reads it to PROMOTE (it needs {@link #liveSyncedFrom} — the staging PATH the live
 * currently mirrors, the deltas' pivot). Built host-side because folding decodes the {@link
 * SeedEnvelope} payloads, which needs the codec + the wire-record classes; incus's cross-run
 * validation reads the same head when built (deferred, I4).
 *
 * <p>Fold rules: the {@code host-live} coordinate has a single logical key (there is one live), so
 * the LAST {@code HostLiveEntry} in the timeline wins. {@code host-staging} / {@code host-drift}
 * are keyed by PATH (the staging root / the drift-report root), so the last entry per path wins — a
 * recycled slot re-materialised at the same path supersedes the old one.
 */
public record HostTreeHead(
    Optional<String> liveSyncedFrom,
    Map<String, HostStagingEntry> stagings,
    Map<String, HostDriftEntry> drifts) {

  /**
   * Fold a parcel's whole timeline (as returned by {@code Cellar.fetch}) into the head. Envelopes
   * of other coordinates are ignored (the parcel may carry harvests too); order is timeline order,
   * so last-wins is a straight overwrite as we walk.
   */
  public static HostTreeHead fold(List<SeedEnvelope> timeline, SeedCodec codec) {
    final Map<String, HostStagingEntry> stagings = new LinkedHashMap<>();
    final Map<String, HostDriftEntry> drifts = new LinkedHashMap<>();
    Optional<String> liveSyncedFrom = Optional.empty();

    for (SeedEnvelope envelope : timeline) {
      switch (envelope.coordinate()) {
        case "host-staging" -> {
          final HostStagingEntry entry = codec.decode(envelope, HostStagingEntry.class);
          stagings.put(entry.stagingRoot(), entry);
        }
        case "host-live" ->
            liveSyncedFrom = Optional.of(codec.decode(envelope, HostLiveEntry.class).syncedFrom());
        case "host-drift" -> {
          final HostDriftEntry entry = codec.decode(envelope, HostDriftEntry.class);
          drifts.put(entry.driftRoot(), entry);
        }
        default -> {
          // Not a host-tree entry (a harvest, a ledger line, …) — not this fold's concern.
        }
      }
    }

    return new HostTreeHead(liveSyncedFrom, Map.copyOf(stagings), Map.copyOf(drifts));
  }
}
