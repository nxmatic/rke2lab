package io.nxmatic.rk2lab.manifests;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MappingIterator;
import io.nxmatic.rk2lab.manifests.api.ManifestDomainPolicy;
import io.nxmatic.rk2lab.manifests.api.ManifestSynthesisRequest;
import io.nxmatic.rk2lab.manifests.api.ManifestSynthesisResult;
import io.nxmatic.rk2lab.manifests.api.ManifestSynthesisService;
import io.nxmatic.rk2lab.manifests.api.ManifestYaml;
import io.nxmatic.rk2lab.manifests.layers.cicd.CicdDomainRegistrar;
import io.nxmatic.rk2lab.manifests.layers.cluster.ClusterDomainRegistrar;
import io.nxmatic.rk2lab.manifests.layers.common.ApplyingManifestUnitVisitor;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomain;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomainRegistry;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomainRegistryBuilder;
import io.nxmatic.rk2lab.manifests.layers.common.ManifestSynthesisContext;
import io.nxmatic.rk2lab.manifests.layers.common.ManifestUnit;
import io.nxmatic.rk2lab.manifests.layers.common.ManifestUnitDependencyApplier;
import io.nxmatic.rk2lab.manifests.layers.common.ManifestUnitRegistry;
import io.nxmatic.rk2lab.manifests.layers.common.ManifestUnitVisitor;
import io.nxmatic.rk2lab.manifests.layers.common.registry.ManifestAssemblyRegistry;
import io.nxmatic.rk2lab.manifests.layers.gitops.GitopsDomainRegistrar;
import io.nxmatic.rk2lab.manifests.layers.ha.HighAvailabilityDomainRegistrar;
import io.nxmatic.rk2lab.manifests.layers.mesh.MeshDomainRegistrar;
import io.nxmatic.rk2lab.manifests.layers.networking.NetworkingDomainRegistrar;
import io.nxmatic.rk2lab.manifests.layers.replication.ReplicationDomainRegistrar;
import io.nxmatic.rk2lab.manifests.layers.runtime.RuntimeDomainRegistrar;
import io.nxmatic.rk2lab.manifests.layers.storage.StorageDomainRegistrar;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.cdk8s.App;
import org.cdk8s.AppProps;
import org.cdk8s.Chart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Default SPI implementation for canonical manifest synthesis. */
public final class DefaultManifestSynthesisService implements ManifestSynthesisService {

  private static final Logger LOG = LoggerFactory.getLogger(DefaultManifestSynthesisService.class);

  private static final Set<String> SCRIPT_DATA_SUFFIXES =
      Set.of(".sh", ".bash", ".env", ".yaml", ".yml", ".conf", ".policy");

  private static final TypeReference<Map<String, Object>> DOCUMENT_TYPE = new TypeReference<>() {};

  @Override
  public String providerId() {
    return "default-cdk8s-synthesizer";
  }

  @Override
  public ManifestSynthesisResult synthesize(ManifestSynthesisRequest request) throws IOException {
    LOG.info(
        "Starting manifests synthesis via provider '{}' (floxDebugPolicy.enabled={})",
        providerId(),
        request.floxDebugPolicy().enabled());

    final ManifestSynthesisContext context =
        ManifestSynthesisContext.of(
            request.floxDebugPolicy(),
            request.bootstrapIdentity(),
            request.networkTopology(),
            request.componentVersions());
    try (var ignored = ManifestSynthesisContext.bind(context)) {
      return synthesizeInContext(request);
    }
  }

  private ManifestSynthesisResult synthesizeInContext(ManifestSynthesisRequest request)
      throws IOException {
    final Path synthOutdir = request.synthOutdir();
    final Path synthManifestFile = request.synthManifestFile();

    final App app = new App(AppProps.builder().outdir(synthOutdir.toString()).build());
    final Chart chart = new Chart(app, "manifests");

    final LayerDomainRegistry configuredDomainRegistry =
        new LayerDomainRegistryBuilder()
            .register(new ClusterDomainRegistrar())
            .register(new StorageDomainRegistrar())
            .register(new ReplicationDomainRegistrar())
            .register(new GitopsDomainRegistrar())
            .register(new RuntimeDomainRegistrar())
            .register(new NetworkingDomainRegistrar())
            .register(new MeshDomainRegistrar())
            .register(new HighAvailabilityDomainRegistrar())
            .register(new CicdDomainRegistrar())
            .build();

    final LayerDomainRegistry domainRegistry =
        applyManifestDomainPolicy(request, configuredDomainRegistry);

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

    enforceLiteralBlockStyleForConfigMapScripts(synthesizedFile);

    Files.createDirectories(synthManifestFile.getParent());
    Files.move(synthesizedFile, synthManifestFile, StandardCopyOption.REPLACE_EXISTING);

    LOG.info(
        "Synthesized manifests from canonical manifest units (manifest unit hits={})",
        manifestUnitHitCount);

    return new ManifestSynthesisResult(
        synthManifestFile, manifestUnitHitCount, domainRegistry.domains().size());
  }

  private LayerDomainRegistry applyManifestDomainPolicy(
      ManifestSynthesisRequest request, LayerDomainRegistry configuredDomainRegistry) {
    if (request.manifestDomainPolicy().isEmpty()) {
      return configuredDomainRegistry;
    }

    final ManifestDomainPolicy manifestDomainPolicy = request.manifestDomainPolicy().orElseThrow();
    final Map<String, LayerDomain> configuredDomainsById =
        configuredDomainRegistry.domains().stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    LayerDomain::domainId,
                    domain -> domain,
                    (left, right) -> left,
                    LinkedHashMap::new));

    final List<String> unknownDomainIds =
        manifestDomainPolicy.domainIds().stream()
            .filter(domainId -> !configuredDomainsById.containsKey(domainId))
            .sorted()
            .toList();
    if (!unknownDomainIds.isEmpty()) {
      throw new IllegalArgumentException(
          "Manifest-domain policy references domains unsupported by provider '"
              + providerId()
              + "': "
              + unknownDomainIds);
    }

    final LinkedHashSet<String> requestedEnabledDomainIds =
        new LinkedHashSet<>(manifestDomainPolicy.enabledDomainIds());
    if (requestedEnabledDomainIds.isEmpty()) {
      throw new IllegalArgumentException(
          "Manifest-domain policy disables all domains for provider '" + providerId() + "'.");
    }

    final LinkedHashSet<String> effectiveDomainIds = new LinkedHashSet<>();
    for (String requestedDomainId : requestedEnabledDomainIds) {
      collectDomainDependencies(requestedDomainId, configuredDomainsById, effectiveDomainIds);
    }

    LOG.info(
        "Applying manifest-domain policy: requested domains={}, effective domains={}",
        requestedEnabledDomainIds,
        effectiveDomainIds);

    final List<LayerDomain> filteredDomains =
        configuredDomainRegistry.domains().stream()
            .filter(domain -> effectiveDomainIds.contains(domain.domainId()))
            .toList();
    return new LayerDomainRegistry(filteredDomains);
  }

  private void collectDomainDependencies(
      String domainId,
      Map<String, LayerDomain> configuredDomainsById,
      Set<String> effectiveDomainIds) {
    if (!effectiveDomainIds.add(domainId)) {
      return;
    }

    final LayerDomain domain = configuredDomainsById.get(domainId);
    if (domain == null) {
      throw new IllegalArgumentException(
          "Unknown manifest domain in policy resolution: " + domainId);
    }

    for (String dependencyDomainId : domain.dependsOnDomainIds()) {
      collectDomainDependencies(dependencyDomainId, configuredDomainsById, effectiveDomainIds);
    }
  }

  private void enforceLiteralBlockStyleForConfigMapScripts(Path synthesizedFile)
      throws IOException {
    final List<Map<String, Object>> documents = new ArrayList<>();
    try (MappingIterator<Map<String, Object>> iterator =
        ManifestYaml.readValues(synthesizedFile, DOCUMENT_TYPE)) {
      while (iterator.hasNext()) {
        final Map<String, Object> document = iterator.next();
        if (document != null) {
          documents.add(normalizeConfigMapScripts(document));
        }
      }
    }

    ManifestYaml.writeDocuments(synthesizedFile, documents);
  }

  private Map<String, Object> normalizeConfigMapScripts(Map<String, Object> document) {
    if (!"ConfigMap".equals(document.get("kind"))) {
      return document;
    }
    final Object data = document.get("data");
    if (!(data instanceof Map<?, ?> dataMap)) {
      return document;
    }

    final LinkedHashMap<String, Object> rewrittenData = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : dataMap.entrySet()) {
      final Object key = entry.getKey();
      final Object value = entry.getValue();
      if (key instanceof String dataKey
          && value instanceof String textValue
          && isScriptLikeConfigMapKey(dataKey)) {
        rewrittenData.put(dataKey, normalizeScriptConfigMapText(textValue));
      } else if (key instanceof String dataKey) {
        rewrittenData.put(dataKey, value);
      }
    }
    document.put("data", rewrittenData);
    return document;
  }

  private boolean isScriptLikeConfigMapKey(String dataKey) {
    final String key = dataKey.toLowerCase(java.util.Locale.ROOT);
    return SCRIPT_DATA_SUFFIXES.stream().anyMatch(key::endsWith) || key.contains("script");
  }

  private String normalizeScriptConfigMapText(String textValue) {
    String normalized = textValue.replace("\r\n", "\n").replace("\r", "\n");
    if (!normalized.contains("\n") && normalized.contains("\\n")) {
      normalized = normalized.replace("\\r\\n", "\n").replace("\\n", "\n");
    }
    if (!normalized.endsWith("\n")) {
      normalized = normalized + "\n";
    }
    return normalized;
  }
}
