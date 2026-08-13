package io.seedmatic.rke2lab.manifests.contract;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Reads manifest YAML through the manifests world's deterministic parser. The byte-deterministic
 * YAML format (sorted keys, literal block scalars, the 64 MiB code-point limit, empty-document
 * coercion) is a guarantee owned by the manifests world — so even a READ goes through this service
 * rather than a host-side {@code ObjectMapper}. The host actualises the parsed structure; it never
 * owns the parser.
 */
public interface ManifestDocumentService {

  /** Stable provider identifier for diagnostics. */
  String providerId();

  /**
   * Parse a single-document manifest YAML file into a generic map.
   *
   * @param yamlSource the YAML file to read
   * @return the document as a {@code Map<String, Object>}
   * @throws IOException if the file cannot be read or parsed
   */
  Map<String, Object> parseDocument(Path yamlSource) throws IOException;
}
