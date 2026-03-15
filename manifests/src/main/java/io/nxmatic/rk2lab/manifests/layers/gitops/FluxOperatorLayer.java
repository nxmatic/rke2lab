// @codebase
package io.nxmatic.rk2lab.manifests.layers.gitops;

import org.cdk8s.Include;
import org.cdk8s.IncludeProps;
import software.constructs.Construct;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public final class FluxOperatorLayer extends Construct {

    public static final String LEGACY_PATH_PREFIX = "gitops/flux-operator/";

    private final String manifestsSubpath = "rke2.d/bioskop/master/manifests.d";

    public FluxOperatorLayer(final Construct scope, final String id) {
        super(scope, id);

        try {
            includeLegacyPackageFiles();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to include legacy manifests for flux-operator", exception);
        }
    }

    private void includeLegacyPackageFiles() throws IOException {
        Path repoRoot = findRepoRoot(Paths.get("").toAbsolutePath().normalize())
                .orElseThrow(() -> new IllegalStateException("Unable to locate repository root containing rke2.d"));

        Path manifestsRoot = repoRoot.resolve(manifestsSubpath);
        Path packageRoot = manifestsRoot.resolve(LEGACY_PATH_PREFIX);
        if (!Files.isDirectory(packageRoot)) {
            throw new IllegalStateException("Expected flux-operator package directory is missing: " + packageRoot);
        }

        List<Path> manifestFiles = collectManifestFiles(packageRoot);
        if (manifestFiles.isEmpty()) {
            throw new IllegalStateException("No .yml manifests found under: " + packageRoot);
        }

        int index = 0;
        for (Path manifestFile : manifestFiles) {
            Path relativePath = packageRoot.relativize(manifestFile);
            String includeId = includeId(index++, relativePath);
            new Include(
                    this,
                    includeId,
                    IncludeProps.builder().url(manifestFile.toAbsolutePath().toString()).build()
            );
        }
    }

    private List<Path> collectManifestFiles(final Path packageRoot) throws IOException {
        try (Stream<Path> stream = Files.walk(packageRoot)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".yml"))
                    .sorted(Comparator.comparing(path -> packageRoot.relativize(path).toString()))
                    .toList();
        }
    }

    private Optional<Path> findRepoRoot(final Path start) {
        Path current = start;
        while (current != null) {
            if (Files.isDirectory(current.resolve("rke2.d"))) {
                return Optional.of(current);
            }
            current = current.getParent();
        }
        return Optional.empty();
    }

    private String includeId(final int index, final Path relativePath) {
        String normalized = relativePath.toString().replace('\\', '/');
        String sanitized = normalized.replaceAll("[^A-Za-z0-9]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        return String.format("flux-operator-%04d-%s", index, sanitized);
    }
}
