package io.nxmatic.rke2lab.pulumi.edge;

import io.nxmatic.rke2lab.doctor.port.SnapshotAccessException;
import io.nxmatic.rke2lab.doctor.port.SnapshotContentException;
import io.nxmatic.rke2lab.doctor.port.SnapshotEntry;
import io.nxmatic.rke2lab.doctor.port.SnapshotSource;
import io.nxmatic.rke2lab.doctor.port.SnapshotView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The canonical Pulumi out-world implementation of the doctor {@link SnapshotSource} port, backed
 * by a {@link StackHandle}. It is the single place that knows both worlds: it translates the host's
 * {@code StackHistory.Entry}/{@code StackSnapshot}/{@code StackException} into the pure {@link
 * SnapshotEntry}/{@link SnapshotView}/{@code SnapshotException} the diagnostic model depends on,
 * and back again. A pure delegate otherwise: it forwards each read to the handle, so a
 * present-but-unreadable history or checkpoint propagates rather than degrading to empty. The only
 * nothing-here is the handle's own absence — an absent history yielding an empty timeline, or a
 * stack with no current state yielding an empty {@code latest()}.
 */
public final class StackHandleSnapshotSource implements SnapshotSource {

  private final StackHandle handle;

  // The pure SnapshotEntry carries only (version, when); on the file backend version is 0 and
  // meaningless, so it cannot identify the origin. We remember the StackHistory.Entry each
  // SnapshotEntry was minted from — keyed by identity (the SnapshotEntry instances handed out by
  // the
  // last timeline()) — so at(...) can re-locate the original to call handle.snapshotOf(...).
  // Rebuilt
  // fresh on every timeline() call.
  private final Map<SnapshotEntry, StackHistory.Entry> origins = new LinkedHashMap<>();

  public StackHandleSnapshotSource(StackHandle handle) {
    this.handle = handle;
  }

  @Override
  public List<SnapshotEntry> timeline() throws SnapshotAccessException, SnapshotContentException {
    final StackHistory history = handle.history();
    final List<StackHistory.Entry> entries;
    try {
      entries = history.entries();
    } catch (StackAccessException e) {
      throw new SnapshotAccessException(history.historyDir().toString(), e);
    } catch (StackContentException e) {
      throw new SnapshotContentException(e.path().toString(), e);
    }

    origins.clear();
    final List<SnapshotEntry> timeline = new ArrayList<>(entries.size());
    for (StackHistory.Entry entry : entries) {
      final SnapshotEntry pure = new SnapshotEntry(entry.version(), entry.when());
      origins.put(pure, entry);
      timeline.add(pure);
    }
    return timeline;
  }

  @Override
  public SnapshotView at(SnapshotEntry entry)
      throws SnapshotAccessException, SnapshotContentException {
    final StackHistory.Entry origin = origins.get(entry);
    if (origin == null) {
      // The caller passed an entry not minted by this source's last timeline() — its origin is
      // unknown, so it cannot be materialized. This is broken content of the request, not an I/O
      // failure: no retry will make a foreign entry resolvable here.
      throw new SnapshotContentException(
          "version=" + entry.version() + " at " + entry.when(),
          new IllegalStateException("entry was not produced by this source's last timeline()"));
    }
    try {
      return viewOf(handle.snapshotOf(origin));
    } catch (StackAccessException e) {
      throw new SnapshotAccessException(origin.file().toString(), e);
    } catch (StackContentException e) {
      throw new SnapshotContentException(origin.file().toString(), e);
    }
  }

  @Override
  public Optional<SnapshotView> latest() throws SnapshotAccessException, SnapshotContentException {
    try {
      return handle.currentSnapshot().map(StackHandleSnapshotSource::viewOf);
    } catch (StackAccessException e) {
      throw new SnapshotAccessException(handle.history().historyDir().toString(), e);
    } catch (StackContentException e) {
      throw new SnapshotContentException(handle.history().historyDir().toString(), e);
    }
  }

  /**
   * Eagerly copies ALL output keys of the snapshot into a {@link SnapshotView}, never
   * pre-filtering: {@code StackSnapshot.outputsNamed} queries one key at a time, so this mirrors
   * its exact traversal (instanceof checks on Map/List, skip non-conforming) for every key at once,
   * in resource order. The two stay behaviorally identical — querying {@code view.outputsNamed(k)}
   * yields the same list {@code snapshot.outputsNamed(k)} would. A null/absent/malformed deployment
   * yields an empty view.
   */
  private static SnapshotView viewOf(StackSnapshot snapshot) {
    final Optional<Map<String, Object>> deploymentOpt = snapshot.deployment();
    if (deploymentOpt.isEmpty()) {
      return new SnapshotView(Map.of());
    }
    final Object resourcesObj = deploymentOpt.get().get("resources");
    if (!(resourcesObj instanceof List<?> resources)) {
      return new SnapshotView(Map.of());
    }

    final Map<String, List<Object>> outputsByKey = new LinkedHashMap<>();
    for (Object resourceObj : resources) {
      if (!(resourceObj instanceof Map<?, ?> resource)) {
        continue;
      }
      if (!(resource.get("outputs") instanceof Map<?, ?> outputs)) {
        continue;
      }
      for (Map.Entry<?, ?> output : outputs.entrySet()) {
        outputsByKey
            .computeIfAbsent(String.valueOf(output.getKey()), k -> new ArrayList<>())
            .add(output.getValue());
      }
    }
    return new SnapshotView(outputsByKey);
  }
}
