package io.nxmatic.rke2lab.manifests;

import io.nxmatic.rke2lab.manifests.contract.ManifestDocumentService;
import java.nio.file.Path;
import java.util.Map;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Default {@link ManifestDocumentService} backed by the deterministic {@link YamlMapper} service.
 */
@Component(service = ManifestDocumentService.class)
public final class DefaultManifestDocumentService implements ManifestDocumentService {

  private final YamlMapper yaml;

  @Activate
  public DefaultManifestDocumentService(@Reference YamlMapper yaml) {
    this.yaml = yaml;
  }

  @Override
  public String providerId() {
    return "default-manifest-yaml";
  }

  @Override
  @SuppressWarnings("unchecked")
  public Map<String, Object> parseDocument(Path yamlSource) {
    return yaml.read(yamlSource, Map.class);
  }
}
