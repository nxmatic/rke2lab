// @codebase
package io.nxmatic.rk2lab.manifests;

import io.nxmatic.rk2lab.manifests.layers.common.ApplyingManifestUnitVisitor;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomainRegistry;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomainRegistryBuilder;
import io.nxmatic.rk2lab.manifests.layers.common.ManifestUnit;
import io.nxmatic.rk2lab.manifests.layers.common.ManifestUnitDependencyApplier;
import io.nxmatic.rk2lab.manifests.layers.common.ManifestUnitRegistry;
import io.nxmatic.rk2lab.manifests.layers.common.ManifestUnitVisitor;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;

public final class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws IOException {
        new ManifestSynthesizer().synthesize();
    }

    private static final class ManifestSynthesizer {

        private final Path synthOutdir = Paths.get(System.getProperty("rk2lab.manifests.outdir", "target"))
                                              .toAbsolutePath()
                                              .normalize();

        private final Path synthManifestFile = Paths.get(
                System.getProperty("rk2lab.manifests.file", "target/manifests.yaml")).toAbsolutePath().normalize();

        void synthesize() throws IOException {
            LOG.info("Starting manifests synthesis");

            App app = new App(AppProps.builder().outdir(synthOutdir.toString()).build());
            Chart chart = new Chart(app, "manifests");

            LayerDomainRegistry domainRegistry = new LayerDomainRegistryBuilder().register(new StorageDomainRegistrar())
                                                                                 .register(
                                                                                         new ReplicationDomainRegistrar())
                                                                                 .register(new GitopsDomainRegistrar())
                                                                                 .register(new RuntimeDomainRegistrar())
                                                                                 .register(
                                                                                         new NetworkingDomainRegistrar())
                                                                                 .register(new MeshDomainRegistrar())
                                                                                 .register(new HaDomainRegistrar())
                                                                                 .register(new CicdDomainRegistrar())
                                                                                 .build();

            List<ManifestUnit> manifestUnits = domainRegistry.manifestUnits()
                                                            .stream()
                                                            .sorted(Comparator.comparing(ManifestUnit::manifestUnitId))
                                                            .toList();

            ManifestUnitRegistry manifestUnitRegistry = new ManifestUnitRegistry(manifestUnits);
            ManifestUnitVisitor manifestUnitVisitor = new ApplyingManifestUnitVisitor();
            ManifestUnitDependencyApplier dependencyApplier = new ManifestUnitDependencyApplier(manifestUnitRegistry,
                    manifestUnitVisitor);

            LOG.info("Configured {} manifest domains", domainRegistry.domains().size());
            LOG.debug("Manifest domains: {}",
                    domainRegistry.domains().stream().map(domain -> domain.domainId()).sorted().toList());

            int manifestUnitHitCount = 0;
            for (ManifestUnit manifestUnit : manifestUnits) {
                manifestUnitHitCount++;
                LOG.debug("Applying manifest unit '{}'", manifestUnit.manifestUnitId());
                domainRegistry.applyManifestUnitWithDomainDependencies(
                        manifestUnit.manifestUnitId(),
                        dependencyApplier,
                        chart
                );
            }

            app.synth();

            Path synthesizedFile = synthOutdir.resolve("manifests.k8s.yaml");
            if (!Files.exists(synthesizedFile)) {
                throw new IllegalStateException("Expected synthesized manifest file is missing: " + synthesizedFile);
            }
            Files.createDirectories(synthManifestFile.getParent());
            Files.move(synthesizedFile, synthManifestFile, StandardCopyOption.REPLACE_EXISTING);

            LOG.info("Synthesized manifests from canonical manifest units (manifest unit hits={})",
                    manifestUnitHitCount);
            LOG.info("Consolidated manifest output written to {}", synthManifestFile);
        }
    }
}
