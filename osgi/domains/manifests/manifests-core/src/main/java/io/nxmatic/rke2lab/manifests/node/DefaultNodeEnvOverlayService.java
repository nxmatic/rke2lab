package io.nxmatic.rke2lab.manifests.node;

import io.nxmatic.rke2lab.manifests.YamlMapper;
import io.nxmatic.rke2lab.manifests.contract.ManifestAnnotations;
import io.nxmatic.rke2lab.manifests.contract.node.NodeEnvContext;
import io.nxmatic.rke2lab.manifests.contract.node.NodeEnvContributor;
import io.nxmatic.rke2lab.manifests.contract.node.NodeEnvOverlayService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Default {@link NodeEnvOverlayService}. The contributor registry and the YAML service arrive by
 * {@link Reference} field injection under SCR.
 */
@Component(service = NodeEnvOverlayService.class)
public final class DefaultNodeEnvOverlayService implements NodeEnvOverlayService {

  private static final String OVERLAY_FILE_NAME =
      "99-configmap-env-section-controlplane-layer-contributions.yml";

  @Reference private NodeEnvContributorRegistry registry;

  @Reference private YamlMapper yaml;

  /** DS activation path: SCR injects {@link #registry} and {@link #yaml}. */
  public DefaultNodeEnvOverlayService() {}

  @Override
  public Map<String, Object> writeControlplaneOverlay(
      Path runtimeEnvConfigRoot, NodeEnvContext layerContext, Map<String, String> seedVariables)
      throws IOException {
    Files.createDirectories(runtimeEnvConfigRoot);

    final List<NodeEnvContributor> orderedContributors = registry.orderedContributors();
    registry.writeAllContributions(runtimeEnvConfigRoot, layerContext);

    // Seed host-resolved variables first; contributor-owned sections override as needed.
    final Map<String, String> aggregatedVars = new LinkedHashMap<>(seedVariables);
    final Map<String, String> layerContributionVars = registry.aggregateContributions(layerContext);
    aggregatedVars.putAll(layerContributionVars);

    final Map<String, Object> annotations = new LinkedHashMap<>();
    annotations.put(ManifestAnnotations.LOCAL_CONFIG, "true");
    annotations.put(
        "description.kpt.dev", "Controlplane runtime environment with layer contributions");
    annotations.put("env.rke2lab.nxmatic.io/section", "section-controlplane-layer-contributions");
    annotations.put("rke2lab.nxmatic.io/managed-by", "controlplane");

    final Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("annotations", annotations);
    metadata.put("name", "env-section-controlplane-layer-contributions");

    final Map<String, Object> document = new LinkedHashMap<>();
    document.put("apiVersion", "v1");
    document.put("kind", "ConfigMap");
    document.put("metadata", metadata);
    document.put("data", aggregatedVars);

    yaml.write(runtimeEnvConfigRoot.resolve(OVERLAY_FILE_NAME)).document(document);

    return buildRegistrySnapshot(orderedContributors, layerContributionVars);
  }

  private static Map<String, Object> buildRegistrySnapshot(
      List<NodeEnvContributor> orderedContributors, Map<String, String> layerContributionVars) {
    final List<Map<String, Object>> contributors = new ArrayList<>();
    final List<String> orderedDomains = new ArrayList<>();
    final List<String> contributedSections = new ArrayList<>();

    for (NodeEnvContributor contributor : orderedContributors) {
      final List<String> sections = List.copyOf(contributor.contributedSections());
      orderedDomains.add(contributor.domainId());
      contributedSections.addAll(sections);
      contributors.add(
          Map.of(
              "domainId", contributor.domainId(),
              "contributorClass", contributor.getClass().getName(),
              "sections", sections,
              "sectionCount", sections.size()));
    }

    return Map.of(
        "contributorCount", contributors.size(),
        "contributors", contributors,
        "orderedDomains", List.copyOf(orderedDomains),
        "contributedSections", List.copyOf(contributedSections),
        "contributedSectionCount", contributedSections.size(),
        "aggregatedVariableCount", layerContributionVars.size());
  }
}
