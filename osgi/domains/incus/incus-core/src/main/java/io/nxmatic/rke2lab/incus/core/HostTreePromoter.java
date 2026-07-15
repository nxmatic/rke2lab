package io.nxmatic.rke2lab.incus.core;

import com.fizzed.jsync.engine.JsyncEngine;
import com.fizzed.jsync.engine.JsyncMode;
import com.fizzed.jsync.engine.JsyncResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * The ACT of the host-tree reconcile — syncs a chosen staging slot into the physical {@code
 * host.live.d} (§ host-cellar-realisation, the reconcile cycle). It only SYNCS: the reconcile scion
 * already decided (a checksum diff of source vs pivot) that a promotion is due, so there is no
 * no-op detection here (main's {@code directoriesAreIdentical} folds into the scion's decision).
 *
 * <p>{@code jsync} with {@code setDelete(true)} gives the rsync-like {@code --delete} — a mount
 * directory present on both sides survives, only its content is synced (R1). The {@code .flox}
 * exclude is the skip-flox the manual walk needed and jsync gives natively (a glob matching {@code
 * .flox/} at any depth): flox runtime state (run/, cache/, …) is neither copied from the staging
 * nor deleted from the live, so the node's live flox environments stay untouched across a
 * promotion.
 *
 * <p>jsync ships a nu manifest (no BSN); it is embedded in this bundle's Bundle-ClassPath, so this
 * class loads {@code com.fizzed.jsync.*} from within the bundle, never as an OSGi import.
 */
public final class HostTreePromoter {

  // The flox runtime-state skip. jsync distinguishes EXCLUDE (do not COPY from the source) from
  // IGNORE (do not touch on EITHER side — copy nor delete). skip-flox needs IGNORE: a --delete pass
  // would otherwise prune the live's .flox/ (exclude is source-only, it does not protect the
  // target).
  // The glob `.flox` matches the DIR at any depth, `.flox/**` its CONTENT — both, so the whole
  // subtree is left untouched on the staging AND on the live.
  private static final String FLOX_DIR = ".flox";
  private static final String FLOX_CONTENT = ".flox/**";

  /**
   * Sync {@code source} (a materialised staging slot) into {@code live} ({@code host.live.d}),
   * deleting stale entries but preserving {@code .flox/} runtime state on both sides. Returns the
   * {@link JsyncResult} (files created/updated/deleted) — the promotion's factual outcome, for the
   * reconcile scion to narrate.
   */
  public JsyncResult promote(Path source, Path live) {
    try {
      return new JsyncEngine()
          .setDelete(true)
          // Compare by CHECKSUM, not mtime: two same-size files differing only in content (a
          // regenerated YAML with an edited value) must sync — an mtime/size heuristic would miss
          // it.
          .setIgnoreTimes(true)
          .addIgnore(FLOX_DIR)
          .addIgnore(FLOX_CONTENT)
          .sync(source, live, JsyncMode.MERGE);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot promote " + source + " → " + live, e);
    }
  }
}
