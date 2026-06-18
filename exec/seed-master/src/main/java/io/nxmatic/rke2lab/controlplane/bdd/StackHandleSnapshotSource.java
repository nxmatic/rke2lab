package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.pulumi.automation.StackAccessException;
import io.nxmatic.rke2lab.pulumi.automation.StackContentException;
import io.nxmatic.rke2lab.pulumi.automation.StackHandle;
import io.nxmatic.rke2lab.pulumi.automation.StackHistory;
import io.nxmatic.rke2lab.pulumi.automation.StackSnapshot;
import java.util.List;
import java.util.Optional;

/**
 * The production {@link SnapshotSource}, backed by a {@link StackHandle}. A pure delegate: it
 * forwards each read to the handle and declares the handle's checked exceptions, so a
 * present-but-unreadable history or checkpoint propagates rather than degrading to empty. The only
 * nothing-here is the handle's own absence — an absent history yielding an empty timeline, or a
 * stack with no current state yielding an empty {@code latest()}.
 */
final class StackHandleSnapshotSource implements SnapshotSource {

  private final StackHandle handle;

  StackHandleSnapshotSource(StackHandle handle) {
    this.handle = handle;
  }

  @Override
  public List<StackHistory.Entry> timeline() throws StackAccessException, StackContentException {
    return handle.history().entries();
  }

  @Override
  public StackSnapshot at(StackHistory.Entry entry)
      throws StackAccessException, StackContentException {
    return handle.snapshotOf(entry);
  }

  @Override
  public Optional<StackSnapshot> latest() throws StackAccessException, StackContentException {
    return handle.currentSnapshot();
  }
}
