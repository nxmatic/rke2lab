// @codebase
package io.nxmatic.rke2lab.manifests.contract;

import java.io.IOException;

/** Service Provider Interface for splitting consolidated synth output into per-resource files. */
public interface ManifestExplodeService {

  /** Stable provider identifier for diagnostics. */
  String providerId();

  /** Splits the consolidated manifest file into one YAML per resource under the target dir. */
  ManifestExplodeResult explode(ManifestExplodeRequest request) throws IOException;
}
