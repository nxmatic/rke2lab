// @codebase
package io.nxmatic.rk2lab.manifests.api;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Request contract for splitting a consolidated multi-document YAML manifest into one file per
 * resource under {@code <layer>/<package>/<order>-<kind>-<name>.yml}.
 *
 * <p>The "layer" and "package" values come from the {@code io.nxmatic.rke2lab/layer} and {@code
 * io.nxmatic.rke2lab/package} annotations the layer code stamps on every emitted resource. The
 * order prefix is determined by kind: {@code 00-} for CustomResourceDefinitions, {@code 01-} for
 * other cluster-scoped resources, {@code 02-} for namespace-scoped resources.
 */
public record ManifestExplodeRequest(Path consolidatedManifestFile, Path explodedTargetDir) {

  public ManifestExplodeRequest {
    Objects.requireNonNull(consolidatedManifestFile, "consolidatedManifestFile");
    Objects.requireNonNull(explodedTargetDir, "explodedTargetDir");
  }
}
