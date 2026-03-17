package io.nxmatic.rk2lab.manifests.api;

import java.nio.file.Path;

/**
 * Result contract for canonical manifest synthesis.
 */
public record ManifestSynthesisResult(Path manifestFile, int manifestUnitHitCount, int domainCount) {
}
