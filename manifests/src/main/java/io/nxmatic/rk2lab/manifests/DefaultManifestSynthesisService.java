package io.nxmatic.rk2lab.manifests;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MappingIterator;
import io.nxmatic.rk2lab.manifests.api.ManifestDomainCatalog;
import io.nxmatic.rk2lab.manifests.api.ManifestDomainPolicy;
import io.nxmatic.rk2lab.manifests.api.ManifestSynthesisRequest;
import io.nxmatic.rk2lab.manifests.api.ManifestSynthesisResult;
import io.nxmatic.rk2lab.manifests.api.ManifestSynthesisService;
import io.nxmatic.rk2lab.manifests.api.ManifestYaml;
import io.nxmatic.rk2lab.manifests.layers.cicd.CicdDomainRegistrar;
import io.nxmatic.rk2lab.manifests.layers.cluster.ClusterDomainRegistrar;
import io.nxmatic.rk2lab.manifests.layers.clusterapi.ClusterApiDomainRegistrar;
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
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdChart;
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
        "Starting manifests synthesis via provider '{}' (floxDebugPolicy={})",
        providerId(),
        request.floxDebugPolicy());

    final ManifestSynthesisContext context =
        ManifestSynthesisContext.of(
            request.floxDebugPolicy(),
            request.bootstrapIdentity(),
            request.networkTopology(),
            request.componentVersions(),
            request.imageState());
    try (var ignored = ManifestSynthesisContext.bind(context)) {
      return synthesizeInContext(request);
    }
  }

  private ManifestSynthesisResult synthesizeInContext(ManifestSynthesisRequest request)
      throws IOException {
    final Path synthOutdir = request.synthOutdir();
    final Path synthManifestFile = request.synthManifestFile();
    final Path systemdOutdir = synthOutdir.resolve("systemd");

    final App app = new App(AppProps.builder().outdir(synthOutdir.toString()).build());
    final Chart chart = new Chart(app, "manifests");
    final SystemdChart systemdChart = new SystemdChart(app, "systemd");

    final LayerDomainRegistry configuredDomainRegistry =
        buildDomainRegistry(request.manifestDomainPolicy().orElse(null));

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

    // Create shared domain catalog FIRST (single source of truth for domain IDs)
    final ManifestDomainCatalog sharedDomainCatalog =
        ManifestDomainCatalog.builder()
            .addDefaultDomains()
            .addDefaultStageALinkableDomains()
            .build();

    // Create targets FIRST (they're referenced by all services)
    LOG.debug("Creating systemd targets");
    final io.nxmatic.rke2lab.cdk8s.systemd.SystemdTarget rke2labTarget =
        new io.nxmatic.rke2lab.cdk8s.systemd.SystemdTarget(systemdChart, "rke2lab")
            .description("RKE2 Lab Bootstrap Target")
            .documentation("https://github.com/nxmatic/rke2lab")
            .wantedBy("multi-user.target");

    // Sub-targets for better organization
    final io.nxmatic.rke2lab.cdk8s.systemd.SystemdTarget bootstrapTarget =
        new io.nxmatic.rke2lab.cdk8s.systemd.SystemdTarget(systemdChart, "rke2lab-bootstrap")
            .description("RKE2 Lab Early Bootstrap (pre-server)")
            .wantedBy(rke2labTarget.getUnitFileName());

    final io.nxmatic.rke2lab.cdk8s.systemd.SystemdTarget manifestsTarget =
        new io.nxmatic.rke2lab.cdk8s.systemd.SystemdTarget(systemdChart, "rke2lab-manifests")
            .description("RKE2 Lab Manifest Installers (post-server)")
            .after("rke2-server.service")
            .requires("rke2-server.service")
            .wantedBy("rke2-server.service");

    final io.nxmatic.rke2lab.cdk8s.systemd.SystemdTarget secretsTarget =
        new io.nxmatic.rke2lab.cdk8s.systemd.SystemdTarget(systemdChart, "rke2lab-secrets")
            .description("RKE2 Lab Secrets Installers (post-server)")
            .after("rke2-server.service")
            .requires("rke2-server.service")
            .wantedBy("rke2-server.service");

    final io.nxmatic.rke2lab.cdk8s.systemd.SystemdTarget networkTarget =
        new io.nxmatic.rke2lab.cdk8s.systemd.SystemdTarget(systemdChart, "rke2lab-network")
            .description("RKE2 Lab Network Infrastructure Target")
            .after("network-online.target")
            .wants("network-online.target");

    final io.nxmatic.rke2lab.cdk8s.systemd.SystemdTarget toolsTarget =
        new io.nxmatic.rke2lab.cdk8s.systemd.SystemdTarget(systemdChart, "rke2lab-tools")
            .description("RKE2 Lab Tools and Utilities Target")
            .after(rke2labTarget.getUnitFileName())
            .wants(rke2labTarget.getUnitFileName());

    // Create synthesis context with target references and shared catalog
    final io.nxmatic.rk2lab.manifests.layers.common.SystemdSynthesisContext systemdContext =
        new io.nxmatic.rk2lab.manifests.layers.common.SystemdSynthesisContext(
            rke2labTarget,
            bootstrapTarget,
            manifestsTarget,
            secretsTarget,
            networkTarget,
            toolsTarget,
            sharedDomainCatalog);

    // Bootstrap and infrastructure services MUST be created first (domains may reference them)
    LOG.debug("Synthesizing bootstrap and infrastructure systemd units");
    new io.nxmatic.rk2lab.manifests.systemd.BootstrapInfrastructureSynthesizer(
            systemdChart, systemdContext)
        .synthesizeAll();

    // Now domains can reference both targets and bootstrap services
    for (LayerDomain domain : domainRegistry.domains()) {
      LOG.debug("Synthesizing systemd units for domain '{}'", domain.domainId());
      domain.synthesizeSystemdUnits(systemdChart, systemdContext);
    }

    LOG.info("Calling app.synth() to synthesize K8s manifests to: {}", synthOutdir);
    app.synth();
    LOG.info("app.synth() completed, now synthesizing systemd units to: {}", systemdOutdir);
    systemdChart.synthesize(systemdOutdir);
    LOG.info("systemdChart.synthesize() completed");

    // CDK8s adds numeric prefixes when multiple charts exist in App tree
    // Find the K8s manifest file with pattern matching
    Path synthesizedFile = null;
    try (var files = Files.list(synthOutdir)) {
      synthesizedFile =
          files
              .filter(
                  p -> {
                    String name = p.getFileName().toString();
                    return name.endsWith("-manifests.k8s.yaml")
                        || name.equals("manifests.k8s.yaml");
                  })
              .findFirst()
              .orElse(null);
    }

    if (synthesizedFile == null || !Files.exists(synthesizedFile)) {
      throw new IllegalStateException(
          "Expected synthesized manifest file (manifests.k8s.yaml or *-manifests.k8s.yaml) is missing in: "
              + synthOutdir);
    }

    enforceLiteralBlockStyleForConfigMapScripts(synthesizedFile);

    Files.createDirectories(synthManifestFile.getParent());
    Files.move(synthesizedFile, synthManifestFile, StandardCopyOption.REPLACE_EXISTING);

    LOG.info(
        "Synthesized K8s manifests and systemd units from canonical manifest units (manifest unit hits={})",
        manifestUnitHitCount);

    return new ManifestSynthesisResult(
        synthManifestFile, systemdOutdir, manifestUnitHitCount, domainRegistry.domains().size());
  }

  private LayerDomainRegistry buildDomainRegistry(ManifestDomainPolicy policy) {
    final ManifestDomainPolicy effectivePolicy =
        policy != null ? policy : ManifestDomainPolicy.builder().build();

    return new LayerDomainRegistryBuilder()
        .register(new ClusterDomainRegistrar(), effectivePolicy)
        .register(new StorageDomainRegistrar(), effectivePolicy)
        .register(new ReplicationDomainRegistrar(), effectivePolicy)
        .register(new GitopsDomainRegistrar(), effectivePolicy)
        .register(new RuntimeDomainRegistrar(), effectivePolicy)
        .register(new NetworkingDomainRegistrar(), effectivePolicy)
        .register(new MeshDomainRegistrar(), effectivePolicy)
        .register(new HighAvailabilityDomainRegistrar(), effectivePolicy)
        .register(new CicdDomainRegistrar(), effectivePolicy)
        .register(new ClusterApiDomainRegistrar(), effectivePolicy)
        .build();
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
