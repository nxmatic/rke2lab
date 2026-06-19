package io.nxmatic.rke2lab.manifests.contract;

import java.io.IOException;

/** Service Provider Interface for manifest synthesis. */
public interface ManifestSynthesisService {

  /** Stable provider identifier for diagnostics and policy checks. */
  String providerId();

  /** Synthesizes canonical manifests using the supplied request. */
  ManifestSynthesisResult synthesize(ManifestSynthesisRequest request) throws IOException;
}
