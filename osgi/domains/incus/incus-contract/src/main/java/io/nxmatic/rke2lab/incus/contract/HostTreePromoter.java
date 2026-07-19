package io.nxmatic.rke2lab.incus.contract;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The incus reconcile's PROMOTE contact: flip a materialised staging slot into the physical {@code
 * host.live.d} the instance mounts. The scion has already DECIDED a promotion is due (a checksum
 * diff of source vs pivot); this contact performs the whole live gesture — it observes the live
 * tree's out-of-band drift against the pivot (reported, never a decision), then syncs the staging
 * in ({@code jsync} MERGE, {@code --delete}, skip-{@code .flox}) — and reports back what actually
 * happened.
 *
 * <p>The mode lives at the FRONTIER, never in the scion: the {@code cultivating} impl does the
 * drift-observe + sync; the {@code surveying} impl touches NOTHING (it reads no live FS, syncs
 * nothing) and reports {@link Promotion#notPromoted()}. The {@code @OsgiService} bridge picks one
 * by LDAP filter on {@code rke2lab.gardening}, so no {@code RunGate} is passed in. The jsync engine
 * is embedded in the impl's bundle ({@code incus-core}); no jsync type crosses this seam — the
 * outcome is the home-vocabulary {@link Promotion}.
 */
public interface HostTreePromoter {

  /**
   * Promote {@code source} (a materialised staging slot) into {@code live} ({@code host.live.d}).
   * Observes the live's drift against {@code pivot} (writing a report beside {@code driftBase}) and
   * syncs. Returns the {@link Promotion} — whether it flipped and any drift observed. A surveying
   * impl returns {@link Promotion#notPromoted()} without touching anything.
   */
  Promotion promote(Path source, Path live, Path pivot, Path driftBase);

  /**
   * The factual outcome of a promote attempt — whether the live tree was actually flipped, and the
   * drift entry observed before the sync (empty when none, or when surveying). It is what the scion
   * narrates (the PROMOTED tag) and commits (the {@code host-live}/{@code host-drift} cellar
   * entries), so the mode never leaks into the scion: it reads {@link #promoted()}, not a gate.
   */
  record Promotion(boolean promoted, Optional<HostDriftEntry> drift) {

    /** A survey (or an inert no-op): nothing flipped, no drift observed. */
    public static Promotion notPromoted() {
      return new Promotion(false, Optional.empty());
    }
  }
}
