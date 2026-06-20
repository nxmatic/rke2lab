package io.nxmatic.rke2lab.manifests.port.node;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Synthesises the controlplane runtime env-config overlay: writes each domain's per-section
 * ConfigMap plus the aggregated {@code 99-configmap} under {@code runtimeEnvConfigRoot}, and
 * returns a snapshot of what the contributor registry produced.
 *
 * <p>This is the manifests world's own operation — building and rendering ConfigMap documents is
 * manifest <em>description</em>, which belongs here, not in the host. The host supplies only its
 * resolved seed variables (policy-derived env + bootstrap constants) and consumes the rendered
 * files; it never serialises YAML nor reaches into the contributor registry.
 */
public interface NodeEnvOverlayService {

  /**
   * Write the layer-contribution ConfigMaps and the aggregated overlay under {@code
   * runtimeEnvConfigRoot}.
   *
   * @param runtimeEnvConfigRoot directory the overlay files are written into (created if absent)
   * @param layerContext read-only node/cluster context handed to each contributor
   * @param seedVariables host-resolved variables seeded before contributions (bootstrap constants +
   *     policy env); contributor-owned sections override these
   * @return a snapshot of the registry result (ordered domains, contributed sections, counts) for
   *     runtime metadata
   * @throws IOException if writing any overlay file fails
   */
  Map<String, Object> writeControlplaneOverlay(
      Path runtimeEnvConfigRoot, NodeEnvContext layerContext, Map<String, String> seedVariables)
      throws IOException;
}
