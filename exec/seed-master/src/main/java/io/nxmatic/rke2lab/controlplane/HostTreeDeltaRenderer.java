package io.nxmatic.rke2lab.controlplane;

import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders a {@link HostTreeDelta} to two SIBLING files named for the generation + role: {@code
 * <base>.json} (the runtime data — the per-file status the node maps to services-to-refresh) and
 * {@code <base>.diff} (the concatenated unified diff — human-readable AND git-applicable, so it is
 * both the operator's read and a patch). {@code <base>} is a generation path like {@code
 * nodeRoot/host.0.change} or {@code nodeRoot/host.0.drift}; the tree it describes is the sibling
 * {@code host.0.staging.d}. So {@code ls host.0.*} shows the tree and its two deltas side by side.
 * The caller records {@code <base>} in a host entry (the delta lives on the FS, the entry carries
 * only its location — § the two deltas). Best-effort is the caller's concern; this writes or
 * throws.
 */
public final class HostTreeDeltaRenderer {

  private final SeedCodec codec = new SeedCodec();

  /**
   * Write {@code delta} as {@code <base>.json} + {@code <base>.diff} and return the {@code .diff}
   * path (the applicable artefact; the {@code .json} sits beside it). {@code base} is a role path
   * like {@code nodeRoot/host.<N>.change} — the suffixes are added here.
   */
  public Path render(Path base, HostTreeDelta delta) {
    try {
      Files.createDirectories(base.getParent());
      Files.writeString(base.resolveSibling(base.getFileName() + ".json"), codec.encode(delta));
      final Path diff = base.resolveSibling(base.getFileName() + ".diff");
      Files.writeString(diff, unifiedDiff(delta));
      return diff;
    } catch (IOException e) {
      throw new UncheckedIOException("cannot render the host-tree delta at " + base, e);
    }
  }

  /**
   * The concatenated unified diff of every entry — the MODIFIED files' diff hunks verbatim, plus a
   * one-line header for the whole-file ADDED / REMOVED changes (which carry no intra-file hunk).
   * Empty when the trees are identical.
   */
  private static String unifiedDiff(HostTreeDelta delta) {
    final List<String> lines = new ArrayList<>();
    for (HostTreeDelta.Entry entry : delta.entries()) {
      switch (entry.status()) {
        case ADDED -> lines.add("+++ " + entry.path() + " (added)");
        case REMOVED -> lines.add("--- " + entry.path() + " (removed)");
        case MODIFIED -> lines.addAll(entry.unifiedDiff());
      }
    }
    return lines.isEmpty() ? "" : String.join("\n", lines) + "\n";
  }
}
