package io.nxmatic.rke2lab.pulumi.automation;

import com.pulumi.automation.AutomationException;
import com.pulumi.automation.LocalWorkspace;
import com.pulumi.automation.StackDeployment;
import com.pulumi.automation.WorkspaceStack;
import io.nxmatic.rke2lab.pulumi.automation.StackHistory.Entry;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Anchors a Pulumi stack and provides unified access to its current snapshot and historical
 * checkpoints.
 *
 * <p>Two modes:
 *
 * <ul>
 *   <li><b>Live mode</b> ({@link #attach attach}): Uses a {@link LocalWorkspace} to read the live
 *       deployment via {@code exportStack()}. History is read from the file backend.
 *   <li><b>File-only mode</b> ({@link #forBackend forBackend}): No live workspace. {@link
 *       #currentSnapshot()} falls back to the latest history entry.
 * </ul>
 *
 * <p>Error contract:
 *
 * <ul>
 *   <li>{@link #currentSnapshot()} returns {@code Optional.empty()} when the stack has no state
 *       (legitimate nothing-here).
 *   <li>{@link #snapshotOf(Entry)} throws {@link StackAccessException} or {@link
 *       StackContentException} when the entry's checkpoint cannot be read (entry exists in history,
 *       so checkpoint is <em>expected</em> readable).
 * </ul>
 */
public final class StackHandle {

  private final String stack;
  private final Path workDir;
  private final StackHistory history;
  private final boolean isLive;

  private StackHandle(String stack, Path workDir, Path backendDir, String project, boolean isLive) {
    this.stack = stack;
    this.workDir = workDir;
    this.history = StackHistory.of(backendDir, project, stack);
    this.isLive = isLive;
  }

  /**
   * Live mode: will use a {@link LocalWorkspace} for {@link #currentSnapshot()}, plus file-backend
   * history for versioned reads.
   */
  public static StackHandle attach(String stack, Path workDir, Path backendDir, String project) {
    return new StackHandle(stack, workDir, backendDir, project, true);
  }

  /**
   * File-only mode: no live workspace. {@link #currentSnapshot()} falls back to the latest history
   * entry.
   */
  public static StackHandle forBackend(Path backendDir, String project, String stack) {
    return new StackHandle(stack, null, backendDir, project, false);
  }

  /**
   * Returns the current snapshot of the stack, or empty if the stack has no state.
   *
   * <p>The two modes differ in which failures they raise: live mode reaches the stack through the
   * Pulumi CLI and can only fail to access it ({@link StackAccessException}); file-only mode also
   * parses the latest checkpoint and can additionally hit malformed content ({@link
   * StackContentException}).
   */
  public Optional<StackSnapshot> currentSnapshot()
      throws StackAccessException, StackContentException {
    if (isLive) {
      return currentSnapshotLive();
    }
    return currentSnapshotFromHistory();
  }

  private Optional<StackSnapshot> currentSnapshotLive() throws StackAccessException {
    try {
      WorkspaceStack workspaceStack = LocalWorkspace.createOrSelectStack(stack, workDir);
      StackDeployment deployment = workspaceStack.exportStack();
      return Optional.of(StackSnapshot.of(deployment));
    } catch (AutomationException e) {
      // exportStack() shells out to the Pulumi CLI and returns an already-parsed
      // StackDeployment — we parse no content here. A failure is therefore reaching or
      // producing the state (CLI absent, process/IO, locked or missing stack): an access
      // problem, retryable. Content failures arise only where we parse raw JSON ourselves
      // (StackCheckpoint).
      throw new StackAccessException(workDir, e);
    }
  }

  private Optional<StackSnapshot> currentSnapshotFromHistory()
      throws StackAccessException, StackContentException {
    List<Entry> entries = history.entries();
    if (entries.isEmpty()) {
      return Optional.empty();
    }

    // entries() is sorted by deployment instant, so the last is the most recent. Never sort by
    // version: the file backend leaves it 0 on every entry, so it cannot identify the latest.
    Entry latest = entries.get(entries.size() - 1);
    return Optional.of(snapshotOf(latest));
  }

  public StackHistory history() {
    return history;
  }

  /**
   * Returns the snapshot for the given history entry.
   *
   * <p>The entry exists in history, so its checkpoint is <em>expected</em> readable. If the
   * checkpoint file is missing or unreadable, throws {@link StackAccessException}.
   *
   * @param entry the history entry
   * @return the snapshot for that entry
   * @throws StackAccessException if the checkpoint file is missing or unreadable
   * @throws StackContentException if the checkpoint content is malformed
   */
  public StackSnapshot snapshotOf(Entry entry) throws StackAccessException, StackContentException {
    Optional<StackCheckpoint> checkpointOpt = history.checkpointOf(entry);
    if (checkpointOpt.isEmpty()) {
      // Entry has no paired checkpoint file — this is an access failure.
      throw new StackAccessException(
          entry.file(), new NoSuchFileException(entry.file().toString()));
    }
    return checkpointOpt.get().snapshot();
  }
}
