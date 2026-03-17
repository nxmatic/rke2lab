package io.nxmatic.rk2lab.manifests.api;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Request contract for canonical manifest synthesis.
 */
public record ManifestSynthesisRequest(Path synthOutdir, Path synthManifestFile) {

    public ManifestSynthesisRequest {
        synthOutdir = synthOutdir.toAbsolutePath().normalize();
        synthManifestFile = synthManifestFile.toAbsolutePath().normalize();
    }

    public static ManifestSynthesisRequest fromSystemProperties() {
        return new ManifestSynthesisRequest(
                Paths.get(System.getProperty("rk2lab.manifests.outdir", "target")),
                Paths.get(System.getProperty("rk2lab.manifests.file", "target/manifests.yaml"))
        );
    }
}
