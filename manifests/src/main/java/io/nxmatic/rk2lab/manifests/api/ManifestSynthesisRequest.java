package io.nxmatic.rk2lab.manifests.api;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
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
        final String outdirProperty = System.getProperty("rk2lab.manifests.outdir");
        final String fileProperty = System.getProperty("rk2lab.manifests.file");

        if (outdirProperty == null && fileProperty == null) {
            return ephemeral();
        }

        if (outdirProperty != null && fileProperty != null) {
            return new ManifestSynthesisRequest(Paths.get(outdirProperty), Paths.get(fileProperty));
        }

        if (outdirProperty != null) {
            final Path outdir = Paths.get(outdirProperty);
            return new ManifestSynthesisRequest(outdir, outdir.resolve("manifests.yaml"));
        }

        final Path manifestFile = Paths.get(fileProperty);
        final Path outdir = manifestFile.getParent() == null ? Paths.get(".") : manifestFile.getParent();
        return new ManifestSynthesisRequest(outdir, manifestFile);
    }

    public static ManifestSynthesisRequest ephemeral() {
        try {
            final Path outdir = Files.createTempDirectory("rk2lab-manifests-").toAbsolutePath().normalize();
            final Path manifestFile = outdir.resolve("manifests.yaml");
            return new ManifestSynthesisRequest(outdir, manifestFile);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to create temporary synthesis directory", ex);
        }
    }
}
