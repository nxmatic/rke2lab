package io.nxmatic.rke2lab.incus.contract;

import io.nxmatic.rke2lab.seed.broker.port.SeedContract;
import java.util.Map;
import java.util.TreeMap;

/**
 * The STAGING entry of the host-tree family — one of the three natures the cellar folds into the
 * host tree's HEAD (see docs/architecture/osgi/host-cellar-realisation-spec.adoc § The host tree
 * the instance mounts). INCUS publishes it for the {@code host.N.staging.d} replica: incus owns the
 * tree (it is what the instance mounts), so after it has grafted the scions' content into the slot
 * it chose, it checksums and publishes this — the CONTRACT of what that staging must contain. It
 * carries only FACETS, never the tree's bytes — the {@code stagingRoot} PATH (the slot incus
 * chose), the per-file {@code checksums} (relative path → SHA-256), and the {@code provenance}
 * (which run produced it) — obeying fetch-not-push, the twin of {@code IncusHarvest.soil}.
 * IMMUTABLE once written (until its slot is recycled by the bounded rotation). Its {@link
 * HostLiveEntry} references the same path by construction (incus chose the slot for both).
 *
 * <p>Because it lives at the cellar and carries checksums, the cellar DESCRIBES the FS in full
 * without holding a byte: incus validates the FS against these checksums before the grow's sync,
 * drift is detectable, and R2 compares evolutions as a checksum diff between two conserved versions
 * — none of it re-reading the tree.
 *
 * <p>{@link SeedContract} binds it to the {@code host-staging} coordinate for the codec's decode
 * guard. The {@code checksums} are held in a sorted map so the serialised form is deterministic
 * (two identical trees → identical payload → a free no-op discriminant). The {@code change} delta
 * (this staging vs {@code live.syncedFrom}) is NOT carried here — it is PRODUCED at the OBSERVE
 * step, not at publication.
 *
 * <p>The twin natures are {@link HostLiveEntry} (which staging the live mirrors) and {@link
 * HostDriftEntry} (the drift the live had before a sync); the reader-side fold is {@link
 * HostTreeHead}.
 */
@SeedContract("host-staging")
public record HostStagingEntry(
    String stagingRoot, Map<String, String> checksums, Provenance provenance) {

  public HostStagingEntry {
    checksums = new TreeMap<>(checksums);
  }

  public static HostStagingEntry of(
      String stagingRoot, Map<String, String> checksums, Provenance provenance) {
    return new HostStagingEntry(stagingRoot, checksums, provenance);
  }

  /**
   * The provenance of a staging — which worktree revision produced it. Only the git {@code sha}
   * (the KEY: commit message / author / date / branch are all recoverable via {@code git show
   * <sha>}, so storing them would duplicate the derivable) plus {@code dirty} (the SOLE bit the sha
   * does NOT carry — a dirty worktree shares the clean HEAD's sha, and a staging CAN legitimately
   * come from a dirty tree when the clean-worktree entry gate is off). The worktree root is NOT
   * here: it is the base, sourced from the stack config, not a trace to freeze. Captured by PREPARE
   * at production, frozen with the immutable staging; the live entry recovers it by transitivity
   * via {@code syncedFrom}. This carries the HISTORY — the fold keeps N stagings, so their
   * provenance survives, whereas {@code liveSyncedFrom} is last-wins.
   */
  public record Provenance(String gitSha, boolean dirty) {}
}
