package io.nxmatic.rke2lab.manifests.contract;

import io.nxmatic.rke2lab.seed.broker.port.SeedContract;
import java.util.Map;
import java.util.TreeMap;

/**
 * The STAGING entry of the host-manifest family — one of the three natures the cellar folds into
 * the host tree's HEAD (see docs/architecture/osgi/host-cellar-realisation-spec.adoc § The host
 * tree the instance mounts). A scion PUBLISHES it for the {@code host.staging.N} replica it
 * materialised: the CONTRACT of what that staging must contain. It carries only FACETS, never the
 * tree's bytes — the {@code stagingRoot} PATH it materialised into (the SOIL the host handed it —
 * the scion echoes the path it knows, it never parses a host slot name), the per-file {@code
 * checksums} (relative path → SHA-256), and the {@code provenance} (which seed/run produced it) —
 * obeying fetch-not-push, the twin of {@code IncusHarvest.soil}. IMMUTABLE once written (until its
 * slot is recycled by the bounded rotation). The host and the scion agree on {@code stagingRoot} by
 * construction: the host CHOSE it (it passed it as the SOIL amendment), so its {@link
 * HostLiveEntry} references the same path — no host vocabulary crosses into the manifests domain.
 *
 * <p>Because it lives at the cellar and carries checksums, the cellar DESCRIBES the FS in full
 * without holding a byte: the incus prep validates the FS against these checksums before the grow's
 * sync, drift is detectable, and R2 compares evolutions as a checksum diff between two conserved
 * versions — none of it re-reading the tree.
 *
 * <p>The manifests scion publishes it (the instance mounts chiefly the synthesis tree, and
 * manifests is the sole scion that materialises an FS tree). {@link SeedContract} binds it to the
 * {@code host-staging} coordinate for the codec's decode guard. The {@code checksums} are held in a
 * sorted map so the serialised form is deterministic (two identical trees → identical payload → a
 * free no-op discriminant). The {@code change} delta (this staging vs {@code live.syncedFrom}) is
 * NOT carried here — it is PRODUCED at the OBSERVE step (I6c), not at publication.
 *
 * <p>The twin natures are {@link HostLiveEntry} (which staging the live mirrors) and {@link
 * HostDriftEntry} (the drift the live had before a sync); the reader-side fold is {@link
 * HostTreeHead}.
 */
@SeedContract("host-staging")
public record HostStagingEntry(
    String stagingRoot, Map<String, String> checksums, String provenance) {

  public HostStagingEntry {
    checksums = new TreeMap<>(checksums);
  }

  public static HostStagingEntry of(
      String stagingRoot, Map<String, String> checksums, String provenance) {
    return new HostStagingEntry(stagingRoot, checksums, provenance);
  }
}
