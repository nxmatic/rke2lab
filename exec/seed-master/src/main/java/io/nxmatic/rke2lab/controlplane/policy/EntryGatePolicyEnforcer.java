package io.nxmatic.rke2lab.controlplane.policy;

import io.nxmatic.rke2lab.worktree.host.WorkingState;
import java.util.List;

/**
 * The clean-git-worktree entry gate. It reads the worktree's {@link WorkingState} — the raw fact
 * the worktree soil harvests into the cellar and the host fetches back — and refuses to provision
 * Stage A with uncommitted manifests generator/resource changes, which Stage A embeds into the seed
 * image (an uncommitted change would provision material that has no committed provenance). The
 * worktree owns the FACT (which paths are uncommitted); this host policy owns the JUDGEMENT (which
 * paths matter). jgit lives only behind the {@code Worktree} component now — the host holds none.
 */
public final class EntryGatePolicyEnforcer {

  private EntryGatePolicyEnforcer() {}

  /**
   * Enforce the clean-worktree gate against the harvested {@link WorkingState}. A no-op when the
   * run does not require a clean worktree, or the tree is clean, or its uncommitted paths touch no
   * embedded manifests resource; otherwise it throws with the offending paths.
   */
  public static void enforceAll(WorkingState workingState, boolean cleanWorktreeRequired) {
    if (!cleanWorktreeRequired || workingState.clean()) {
      return;
    }
    final List<String> relevantChanges =
        workingState.uncommittedPaths().stream()
            .filter(EntryGatePolicyEnforcer::isEmbeddedManifestResourcePath)
            .toList();
    if (relevantChanges.isEmpty()) {
      return;
    }
    throw new IllegalStateException(
        "Entry-gate policy failed (clean-git-worktree): Pulumi update requires a clean manifests"
            + " module worktree for Stage A. Resolve or commit manifests generator/resource changes"
            + " before running.\nRelevant paths:\n- "
            + String.join("\n- ", relevantChanges));
  }

  private static boolean isEmbeddedManifestResourcePath(String path) {
    return path != null
        && (path.startsWith("manifests/src/main/resources/")
            || path.startsWith("manifests/src/main/java/")
            || "manifests/src/main/resources".equals(path));
  }
}
