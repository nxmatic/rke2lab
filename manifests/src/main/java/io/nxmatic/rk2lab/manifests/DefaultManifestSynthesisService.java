package io.nxmatic.rk2lab.manifests;

import io.nxmatic.rk2lab.manifests.api.ManifestSynthesisRequest;
import io.nxmatic.rk2lab.manifests.api.ManifestSynthesisResult;
import io.nxmatic.rk2lab.manifests.api.ManifestSynthesisService;
import io.nxmatic.rk2lab.manifests.layers.cicd.CicdDomainRegistrar;
import io.nxmatic.rk2lab.manifests.layers.cluster.ClusterDomainRegistrar;
import io.nxmatic.rk2lab.manifests.layers.common.ApplyingManifestUnitVisitor;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomainRegistry;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomainRegistryBuilder;
import io.nxmatic.rk2lab.manifests.layers.common.ManifestUnit;
import io.nxmatic.rk2lab.manifests.layers.common.ManifestUnitDependencyApplier;
import io.nxmatic.rk2lab.manifests.layers.common.ManifestUnitRegistry;
import io.nxmatic.rk2lab.manifests.layers.common.ManifestUnitVisitor;
import io.nxmatic.rk2lab.manifests.layers.common.registry.ManifestAssemblyRegistry;
import io.nxmatic.rk2lab.manifests.layers.gitops.GitopsDomainRegistrar;
import io.nxmatic.rk2lab.manifests.layers.ha.HaDomainRegistrar;
import io.nxmatic.rk2lab.manifests.layers.mesh.MeshDomainRegistrar;
import io.nxmatic.rk2lab.manifests.layers.networking.NetworkingDomainRegistrar;
import io.nxmatic.rk2lab.manifests.layers.replication.ReplicationDomainRegistrar;
import io.nxmatic.rk2lab.manifests.layers.runtime.RuntimeDomainRegistrar;
import io.nxmatic.rk2lab.manifests.layers.storage.StorageDomainRegistrar;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import org.cdk8s.App;
import org.cdk8s.AppProps;
import org.cdk8s.Chart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Default SPI implementation for canonical manifest synthesis. */
public final class DefaultManifestSynthesisService implements ManifestSynthesisService {

  private static final Logger LOG = LoggerFactory.getLogger(DefaultManifestSynthesisService.class);

  @Override
  public String providerId() {
    return "default-cdk8s-synthesizer";
  }

  @Override
  public ManifestSynthesisResult synthesize(ManifestSynthesisRequest request) throws IOException {
    LOG.info("Starting manifests synthesis via provider '{}'", providerId());

    final Path synthOutdir = request.synthOutdir();
    final Path synthManifestFile = request.synthManifestFile();

    final App app = new App(AppProps.builder().outdir(synthOutdir.toString()).build());
    final Chart chart = new Chart(app, "manifests");

    final LayerDomainRegistry domainRegistry =
        new LayerDomainRegistryBuilder()
            .register(new ClusterDomainRegistrar())
            .register(new StorageDomainRegistrar())
            .register(new ReplicationDomainRegistrar())
            .register(new GitopsDomainRegistrar())
            .register(new RuntimeDomainRegistrar())
            .register(new NetworkingDomainRegistrar())
            .register(new MeshDomainRegistrar())
            .register(new HaDomainRegistrar())
            .register(new CicdDomainRegistrar())
            .build();

    final List<ManifestUnit> manifestUnits =
        domainRegistry.manifestUnits().stream()
            .sorted(Comparator.comparing(ManifestUnit::manifestUnitId))
            .toList();

    final ManifestAssemblyRegistry assemblyRegistry = new ManifestAssemblyRegistry();
    final ManifestUnitRegistry manifestUnitRegistry = new ManifestUnitRegistry(manifestUnits);
    final ManifestUnitVisitor manifestUnitVisitor = new ApplyingManifestUnitVisitor();
    final ManifestUnitDependencyApplier dependencyApplier =
        new ManifestUnitDependencyApplier(
            domainRegistry, manifestUnitRegistry, manifestUnitVisitor, chart, assemblyRegistry);

    LOG.info("Configured {} manifest domains", domainRegistry.domains().size());
    LOG.debug(
        "Manifest domains: {}",
        domainRegistry.domains().stream().map(domain -> domain.domainId()).sorted().toList());

    int manifestUnitHitCount = 0;
    for (ManifestUnit manifestUnit : manifestUnits) {
      manifestUnitHitCount++;
      LOG.debug("Applying manifest unit '{}'", manifestUnit.manifestUnitId());
      domainRegistry.applyManifestUnitWithDomainDependencies(
          manifestUnit.manifestUnitId(), dependencyApplier);
    }

    app.synth();

    final Path synthesizedFile = synthOutdir.resolve("manifests.k8s.yaml");
    if (!Files.exists(synthesizedFile)) {
      throw new IllegalStateException(
          "Expected synthesized manifest file is missing: " + synthesizedFile);
    }

    Files.createDirectories(synthManifestFile.getParent());
    Files.move(synthesizedFile, synthManifestFile, StandardCopyOption.REPLACE_EXISTING);

    LOG.info(
        "Synthesized manifests from canonical manifest units (manifest unit hits={})",
        manifestUnitHitCount);

    return new ManifestSynthesisResult(
        synthManifestFile, manifestUnitHitCount, domainRegistry.domains().size());
  }
}
