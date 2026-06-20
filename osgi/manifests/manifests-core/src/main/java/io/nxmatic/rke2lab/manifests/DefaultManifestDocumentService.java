package io.nxmatic.rke2lab.manifests;

import io.nxmatic.rke2lab.manifests.port.ManifestDocumentService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.osgi.service.component.annotations.Component;

/**
 * Default {@link ManifestDocumentService} backed by the deterministic {@link ManifestYaml} mapper.
 */
@Component(service = ManifestDocumentService.class)
public final class DefaultManifestDocumentService implements ManifestDocumentService {

  @Override
  public String providerId() {
    return "default-manifest-yaml";
  }

  @Override
  public Map<String, Object> parseDocument(Path yamlSource) throws IOException {
    @SuppressWarnings("unchecked")
    final Map<String, Object> parsed =
        ManifestYaml.mapper().readValue(yamlSource.toFile(), Map.class);
    return parsed;
  }
}
