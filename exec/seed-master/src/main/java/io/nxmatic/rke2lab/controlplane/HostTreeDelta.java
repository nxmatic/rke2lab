package io.nxmatic.rke2lab.controlplane;

import java.util.List;

/**
 * The delta between two host trees — the DATA the OBSERVE step produces before the grow ACTS (see
 * docs/architecture/osgi/host-cellar-realisation-spec.adoc § The two deltas). It is NOT a jGiven
 * {@code ReportModel}: a tree delta is data, not a played scenario. It is rendered to the FS (json
 * for the runtime, adoc for the operator) and its LOCATION is carried in a host entry; a scenario
 * Then-step asserts it, so it still appears in the runbook by reference (the runbook names the
 * delta, it does not embed it).
 *
 * <p>Two instances serve the grow, both pivoting on {@code host.{live.syncedFrom}.staging.d}: the
 * {@code change} ({@code staging.N} vs the pivot — the run's intended change) and the {@code drift}
 * (the physical {@code host.live.d} vs the pivot — the live's out-of-band deviation). Each is the
 * same shape: per-file {@link Entry entries}, each a {@link Status} and, for a MODIFIED text file,
 * the unified-diff lines.
 */
public record HostTreeDelta(String fromRoot, String toRoot, List<Entry> entries) {

  public HostTreeDelta {
    entries = List.copyOf(entries);
  }

  /** The status of one file across the two trees. */
  public enum Status {
    ADDED,
    REMOVED,
    MODIFIED
  }

  /**
   * One file's delta: its tree-relative POSIX {@code path}, its {@link Status}, and — only for a
   * MODIFIED text file — the {@code unifiedDiff} lines (empty otherwise: ADDED/REMOVED carry no
   * intra-file diff, and a MODIFIED binary is reported by status alone).
   */
  public record Entry(String path, Status status, List<String> unifiedDiff) {

    public Entry {
      unifiedDiff = List.copyOf(unifiedDiff);
    }

    public static Entry added(String path) {
      return new Entry(path, Status.ADDED, List.of());
    }

    public static Entry removed(String path) {
      return new Entry(path, Status.REMOVED, List.of());
    }

    public static Entry modified(String path, List<String> unifiedDiff) {
      return new Entry(path, Status.MODIFIED, unifiedDiff);
    }
  }

  /** No differences — the two trees are identical (a no-op run, or an undrifted live). */
  public boolean isEmpty() {
    return entries.isEmpty();
  }
}
