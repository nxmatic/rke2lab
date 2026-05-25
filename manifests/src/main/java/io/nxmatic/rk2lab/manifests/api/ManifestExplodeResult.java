// @codebase
package io.nxmatic.rk2lab.manifests.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Result contract for {@link ManifestExplodeService#explode(ManifestExplodeRequest)}. */
public record ManifestExplodeResult(Path explodedTargetDir, List<Path> writtenFiles) {

  public ManifestExplodeResult {
    Objects.requireNonNull(explodedTargetDir, "explodedTargetDir");
    writtenFiles = List.copyOf(writtenFiles);
  }

  public int writtenFileCount() {
    return writtenFiles.size();
  }
}
