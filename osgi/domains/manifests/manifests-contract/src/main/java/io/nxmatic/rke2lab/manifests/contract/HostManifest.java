package io.nxmatic.rke2lab.manifests.contract;

import io.nxmatic.rke2lab.seed.broker.port.SeedContract;
import java.util.Map;
import java.util.TreeMap;

/**
 * The host-manifest a scion PUBLISHES to the cellar for the {@code host.staging.{SN}} replica it
 * materialised — the CONTRACT of what that staging must contain (see
 * docs/architecture/osgi/host-cellar-realisation-spec.adoc § The host tree the instance mounts). It
 * carries only FACETS, never the tree's bytes: the {@code stagingRoot} it describes, the per-file
 * {@code checksums} (relative path → SHA-256), and the {@code provenance} (which seed/run produced
 * it). This obeys fetch-not-push — a pointer + checksums, never the cultivated files — the twin of
 * {@code IncusHarvest.soil}.
 *
 * <p>Because the manifest lives at the cellar and carries checksums, the cellar DESCRIBES the FS in
 * full without holding a byte: the incus prep validates the FS against these checksums before the
 * grow's rsync, drift is detectable, and R2 compares evolutions as a checksum diff between two
 * conserved versions — none of it re-reading the tree.
 *
 * <p>The manifests scion publishes it (the instance mounts chiefly the synthesis tree, and
 * manifests is the sole scion that materialises an FS tree). {@link SeedContract} binds it to the
 * {@code host-manifest} coordinate for the codec's decode guard. The {@code checksums} are held in
 * a sorted map so the serialised form is deterministic (two identical trees → identical payload → a
 * free no-op discriminant).
 */
@SeedContract("host-manifest")
public record HostManifest(String stagingRoot, Map<String, String> checksums, String provenance) {

  public HostManifest {
    checksums = new TreeMap<>(checksums);
  }

  public static HostManifest of(
      String stagingRoot, Map<String, String> checksums, String provenance) {
    return new HostManifest(stagingRoot, checksums, provenance);
  }
}
