package io.seedmatic.rke2lab.manifests.contract;

import java.nio.file.Path;

/**
 * The node-side bootstrap artifact the exploder writes and the synthesis scion reads back — an enum
 * so its location is one instance both ends resolve through, never a static path helper (the
 * instance-discipline law: pass instances, not static behaviour). It sits a level ABOVE the
 * exploded tree, like the consolidated {@code manifests.yaml}, so it is never part of the
 * committed/applied per-resource branch tree.
 */
public enum NodeBootstrapArtifact {

  /**
   * The single multi-doc file the exploder collects the {@link ManifestAnnotation#NODE_BOOTSTRAP}
   * resources into, for the host to seed onto the node over devlxd (see the {@code
   * rke2lab-server-manifests} guest unit).
   */
  MANIFESTS(".bootstrap", "rke2lab-bootstrap.yaml");

  private final String dir;
  private final String file;

  NodeBootstrapArtifact(String dir, String file) {
    this.dir = dir;
    this.file = file;
  }

  /**
   * This artifact's path for a given exploded tree — its sibling one level above (so it is not part
   * of the tree). Falls back into the tree itself only when it has no parent (a bare temp-dir
   * render with no delivery), where nothing is committed anyway.
   */
  public Path in(final Path explodedTargetDir) {
    final Path base =
        explodedTargetDir.getParent() == null ? explodedTargetDir : explodedTargetDir.getParent();
    return base.resolve(dir).resolve(file);
  }
}
