// @codebase
package io.nxmatic.rk2lab.manifests;

import io.nxmatic.rk2lab.manifests.layers.common.ApplyingLayerVisitor;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDependencyApplier;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomainRegistry;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomainRegistryBuilder;
import io.nxmatic.rk2lab.manifests.layers.common.LayerRegistry;
import io.nxmatic.rk2lab.manifests.layers.common.LayerVisitor;
import io.nxmatic.rk2lab.manifests.layers.common.ModeledLayer;
import io.nxmatic.rk2lab.manifests.layers.cicd.CicdDomainRegistrar;
import io.nxmatic.rk2lab.manifests.layers.gitops.GitopsDomainRegistrar;
import io.nxmatic.rk2lab.manifests.layers.ha.HaDomainRegistrar;
import io.nxmatic.rk2lab.manifests.layers.mesh.MeshDomainRegistrar;
import io.nxmatic.rk2lab.manifests.layers.networking.NetworkingDomainRegistrar;
import io.nxmatic.rk2lab.manifests.layers.replication.ReplicationDomainRegistrar;
import io.nxmatic.rk2lab.manifests.layers.runtime.RuntimeDomainRegistrar;
import io.nxmatic.rk2lab.manifests.layers.storage.StorageDomainRegistrar;
import org.cdk8s.App;
import org.cdk8s.AppProps;
import org.cdk8s.Chart;
import org.cdk8s.Include;
import org.cdk8s.IncludeProps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public final class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws IOException {
        new ManifestSynthesizer().synthesize();
    }

    private static final class ManifestSynthesizer {

        private final String manifestsSubpath = "rke2.d/bioskop/master/manifests.d";
        private final Path synthOutdir = Paths.get(System.getProperty("rk2lab.manifests.outdir", "target")).toAbsolutePath().normalize();
        private final Path synthManifestFile = Paths.get(System.getProperty("rk2lab.manifests.file", "target/manifests.yaml")).toAbsolutePath().normalize();

        void synthesize() throws IOException {
            LOG.info("Starting manifests synthesis");

            Path repoRoot = findRepoRoot(Paths.get("").toAbsolutePath().normalize())
                    .orElseThrow(() -> new IllegalStateException("Unable to locate repository root containing rke2.d"));

            Path manifestsRoot = repoRoot.resolve(manifestsSubpath);
            if (!Files.isDirectory(manifestsRoot)) {
                throw new IllegalStateException("Expected manifests directory is missing: " + manifestsRoot);
            }

            List<Path> manifestFiles = collectManifestFiles(manifestsRoot);
            if (manifestFiles.isEmpty()) {
                throw new IllegalStateException("No .yml manifests found under: " + manifestsRoot);
            }

                App app = new App(AppProps.builder()
                    .outdir(synthOutdir.toString())
                    .build());
                Chart chart = new Chart(app, "manifests");

            LayerDomainRegistry domainRegistry = new LayerDomainRegistryBuilder()
                    .register(new StorageDomainRegistrar())
                    .register(new ReplicationDomainRegistrar())
                    .register(new GitopsDomainRegistrar())
                    .register(new RuntimeDomainRegistrar())
                    .register(new NetworkingDomainRegistrar())
                    .register(new MeshDomainRegistrar())
                    .register(new HaDomainRegistrar())
                    .register(new CicdDomainRegistrar())
                    .build();

            List<ModeledLayer> modeledLayers = domainRegistry.modeledLayers();

            LayerRegistry layerRegistry = new LayerRegistry(modeledLayers);
            LayerVisitor layerVisitor = new ApplyingLayerVisitor();
            LayerDependencyApplier dependencyApplier = new LayerDependencyApplier(layerRegistry, layerVisitor);

            LOG.info("Configured {} modeled domains", domainRegistry.domains().size());
            LOG.debug("Modeled domains: {}", domainRegistry.domains().stream()
                    .map(domain -> domain.domainId())
                    .sorted()
                    .toList());

            IncludeSequence includeSequence = new IncludeSequence();
            int modeledLayerHitCount = 0;
            int legacyIncludeCount = 0;
            for (Path manifestFile : manifestFiles) {
                Path relativePath = manifestsRoot.relativize(manifestFile);
                String relativePathString = relativePath.toString().replace('\\', '/');

                Optional<ModeledLayer> modeledLayer = layerRegistry.findByLegacyPath(relativePathString);
                if (modeledLayer.isPresent()) {
                    modeledLayerHitCount++;
                    LOG.debug("Applying modeled layer '{}' for manifest path '{}'", modeledLayer.get().layerId(), relativePathString);
                    domainRegistry.applyLayerWithDomainDependencies(
                            modeledLayer.get().layerId(),
                            dependencyApplier,
                            chart
                    );
                    continue;
                }

                String includeId = includeSequence.nextIncludeId(relativePath);
                legacyIncludeCount++;
                LOG.debug("Including legacy manifest '{}' as '{}'", relativePathString, includeId);
                new Include(
                        chart,
                        includeId,
                        IncludeProps.builder().url(manifestFile.toAbsolutePath().toString()).build()
                );
            }

            app.synth();

            Path synthesizedFile = synthOutdir.resolve("manifests.k8s.yaml");
            if (!Files.exists(synthesizedFile)) {
                throw new IllegalStateException("Expected synthesized manifest file is missing: " + synthesizedFile);
            }
            Files.createDirectories(synthManifestFile.getParent());
            Files.move(synthesizedFile, synthManifestFile, StandardCopyOption.REPLACE_EXISTING);

            LOG.info(
                    "Synthesized {} manifest files from {} (modeled hits={}, legacy includes={})",
                    manifestFiles.size(),
                    manifestsRoot,
                    modeledLayerHitCount,
                    legacyIncludeCount
                );
            LOG.info("Consolidated manifest output written to {}", synthManifestFile);
        }

        private List<Path> collectManifestFiles(final Path manifestsRoot) throws IOException {
            try (Stream<Path> stream = Files.walk(manifestsRoot)) {
                return stream
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".yml"))
                        .sorted(Comparator.comparing(path -> manifestsRoot.relativize(path).toString()))
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
    }

    private static final class IncludeSequence {

        private int index = 0;

        String nextIncludeId(final Path relativePath) {
            String normalized = relativePath.toString().replace('\\', '/');
            String sanitized = normalized.replaceAll("[^A-Za-z0-9]+", "-")
                    .replaceAll("^-+", "")
                    .replaceAll("-+$", "");
            return String.format("m-%04d-%s", index++, sanitized);
        }
    }
}
