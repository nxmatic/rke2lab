// @codebase
package io.seedmatic.rke2lab.manifests.contract;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Metadata annotations for rke2lab manifest resources.
 *
 * <p>These annotations enable manifest exploding (splitting consolidated YAML into per-resource
 * files organized by domain/package).
 *
 * <p>A stateless record — the annotation keys are compile-time constants (composable at
 * static-init), and {@link #packageAnnotations} is the pure map-building behavior a manifest unit
 * calls. Record, not a plain class, so it stays on the contract face (contract-purity: the seam
 * carries records / enums / interfaces, never a concrete class).
 */
public record ManifestAnnotations() {

  public static final String DOMAIN = "io.seedmatic.rke2lab/domain";
  public static final String PACKAGE = "io.seedmatic.rke2lab/package";

  /**
   * Marks a manifest that the cluster must not apply (kpt convention). {@code
   * rke2lab-manifests-install.sh} skips symlinking these into the RKE2 server manifests dir, and
   * the exploder writes them as hidden dotfiles by default.
   */
  public static final String LOCAL_CONFIG = "config.kubernetes.io/local-config";

  /**
   * Marks a ConfigMap whose {@code .data} entries are RKE2 server config fragments. The exploder
   * writes these verbatim (visible {@code <name>}, no dotfile) so {@code rke2lab-config-install.sh}
   * can glob them into {@code /etc/rancher/rke2/config.yaml.d}.
   */
  public static final String RKE2_CONFIG = "io.seedmatic.rke2lab/rke2-config";

  /**
   * Marks the per-unit group inventory ConfigMap emitted by {@link AbstractManifestsUnit}. Carries
   * {@link #LOCAL_CONFIG} too, so it is never applied to the cluster; this annotation identifies it
   * as an inventory marker rather than a real local-config resource.
   */
  public static final String MANIFEST_GROUP = "io.seedmatic.rke2lab/manifest-group";

  public Map<String, String> packageAnnotations(final String domain, final String packageName) {
    return packageAnnotations(domain, packageName, Map.of());
  }

  public Map<String, String> packageAnnotations(
      final String domain, final String packageName, final Map<String, String> extraAnnotations) {
    LinkedHashMap<String, String> annotations = new LinkedHashMap<>();
    annotations.put(DOMAIN, domain);
    annotations.put(PACKAGE, packageName);
    annotations.putAll(extraAnnotations);
    return Map.copyOf(annotations);
  }
}
