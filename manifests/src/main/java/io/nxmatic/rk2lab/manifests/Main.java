// @codebase
package io.nxmatic.rk2lab.manifests;

import io.nxmatic.rk2lab.manifests.api.ManifestSynthesisRequest;
import io.nxmatic.rk2lab.manifests.api.ManifestSynthesisResult;
import io.nxmatic.rk2lab.manifests.api.ManifestSynthesisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.ServiceLoader;

public final class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);
    private static final List<EmbeddedAsset> SHIM_ASSETS = List.of(
            new EmbeddedAsset("/runtime/flox-containerd-shim/flox-shim-build.sh", "flox-shim-build.sh", true),
            new EmbeddedAsset("/runtime/flox-containerd-shim/flox-shim-build.yaml", "flox-shim-build.yaml", false),
            new EmbeddedAsset("/runtime/flox-containerd-shim/shim-installer.sh", "shim-installer.sh", true),
            new EmbeddedAsset("/runtime/flox-containerd-shim/shim-installer-host.sh", "shim-installer-host.sh", true),
            new EmbeddedAsset("/runtime/flox-containerd-shim/mesh/headplane/flake.nix", "mesh/headplane/flake.nix", false),
            new EmbeddedAsset("/runtime/flox-containerd-shim/networking/kdns/flake.nix", "networking/kdns/flake.nix", false)
    );

    public static void main(String[] args) throws IOException {
        if (args.length > 0) {
            final String command = args[0];
            if ("materialize-shim-assets".equals(command)) {
                final Path outputDir = args.length > 1
                        ? Paths.get(args[1])
                        : Paths.get(".");
                materializeShimAssets(outputDir);
                return;
            }
            throw new IllegalArgumentException("Unknown command: " + command
                    + ". Supported commands: materialize-shim-assets");
        }

        final ManifestSynthesisService synthesisService = loadRequiredSingleProvider();
        final ManifestSynthesisResult result = synthesisService.synthesize(ManifestSynthesisRequest.fromSystemProperties());
        LOG.info("Manifest synthesis completed by provider '{}'", synthesisService.providerId());
        LOG.info("Consolidated manifest output written to {}", result.manifestFile());
    }

    private static void materializeShimAssets(Path outputDir) throws IOException {
        final Path normalizedOutputDir = outputDir.toAbsolutePath().normalize();
        Files.createDirectories(normalizedOutputDir);

        for (EmbeddedAsset asset : SHIM_ASSETS) {
            final Path targetPath = normalizedOutputDir.resolve(asset.relativePath()).normalize();
            Files.createDirectories(targetPath.getParent());

            try (InputStream in = Main.class.getResourceAsStream(asset.classpathResource())) {
                if (in == null) {
                    throw new IllegalStateException("Missing embedded resource: " + asset.classpathResource());
                }
                Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }

            if (asset.executable()) {
                try {
                    Files.setPosixFilePermissions(targetPath, Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE,
                            PosixFilePermission.GROUP_READ,
                            PosixFilePermission.GROUP_EXECUTE,
                            PosixFilePermission.OTHERS_READ,
                            PosixFilePermission.OTHERS_EXECUTE));
                } catch (UnsupportedOperationException ex) {
                    // Non-POSIX filesystem; best effort only.
                }
            }
        }

        LOG.info("Materialized {} shim assets to {}", SHIM_ASSETS.size(), normalizedOutputDir);
    }

    private static ManifestSynthesisService loadRequiredSingleProvider() {
        final List<ManifestSynthesisService> providers = ServiceLoader.load(ManifestSynthesisService.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .toList();
        if (providers.isEmpty()) {
            throw new IllegalStateException("No ManifestSynthesisService provider found via ServiceLoader.");
        }
        if (providers.size() > 1) {
            throw new IllegalStateException("Expected exactly one ManifestSynthesisService provider, found "
                    + providers.size() + ": "
                    + providers.stream().map(ManifestSynthesisService::providerId).toList());
        }
        return providers.getFirst();
    }

    private record EmbeddedAsset(String classpathResource, String relativePath, boolean executable) {
    }
}
