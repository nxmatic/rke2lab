package io.nxmatic.rke2lab.manifests;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.nxmatic.rke2lab.manifests.port.ManifestAnnotations;
import io.nxmatic.rke2lab.manifests.port.ManifestExplodeRequest;
import io.nxmatic.rke2lab.manifests.port.ManifestExplodeResult;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Locks the annotation-driven file naming in {@link DefaultManifestExplodeService}: the
 * per-resource file name is decided by annotations, never by the resource name. Regression guard
 * for the empty {@code config.yaml.d} bug, where RKE2 config fragments were dotfiled into {@code
 * .configmap-<name>.yaml.yml} and missed the installer's {@code *.yaml}/{@code *.yml} glob.
 */
class DefaultManifestExplodeServiceTest {

  @Test
  void rke2ConfigFragmentKeepsVerbatimName(@TempDir Path tmp) throws IOException {
    // RKE2_CONFIG wins over LOCAL_CONFIG: the fragment stays visible (globbable) even though it
    // carries local-config so the cluster never applies it.
    final Map<String, Object> document =
        resource(
            "ConfigMap",
            "core.yaml",
            null,
            "runtime",
            "rke2-config",
            Map.of(
                ManifestAnnotations.LOCAL_CONFIG, "true",
                ManifestAnnotations.RKE2_CONFIG, "true"));

    assertEquals("runtime/rke2-config/core.yaml", explodeOne(tmp, document));
  }

  @Test
  void groupMarkerBecomesHiddenDotfile(@TempDir Path tmp) throws IOException {
    final Map<String, Object> document =
        resource(
            "ConfigMap",
            "rke2-config.group",
            null,
            "runtime",
            "rke2-config",
            Map.of(
                ManifestAnnotations.LOCAL_CONFIG, "true",
                ManifestAnnotations.MANIFEST_GROUP, "true"));

    assertEquals("runtime/rke2-config/.configmap-rke2-config.group.yml", explodeOne(tmp, document));
  }

  @Test
  void localConfigBecomesHiddenDotfile(@TempDir Path tmp) throws IOException {
    final Map<String, Object> document =
        resource(
            "ConfigMap",
            "cloud-config",
            null,
            "runtime",
            "cloud-config",
            Map.of(ManifestAnnotations.LOCAL_CONFIG, "true"));

    assertEquals("runtime/cloud-config/.configmap-cloud-config.yml", explodeOne(tmp, document));
  }

  @Test
  void namespaceScopedResourceGetsOrder02(@TempDir Path tmp) throws IOException {
    final Map<String, Object> document =
        resource("ConfigMap", "headscale-config", "mesh-system", "mesh", "headscale", Map.of());

    assertEquals("mesh/headscale/02-configmap-headscale-config.yml", explodeOne(tmp, document));
  }

  @Test
  void clusterScopedResourceGetsOrder01(@TempDir Path tmp) throws IOException {
    final Map<String, Object> document =
        resource(
            "Namespace", "rke2lab-system", null, "cluster", "runtime-system-namespace", Map.of());

    assertEquals(
        "cluster/runtime-system-namespace/01-namespace-rke2lab-system.yml",
        explodeOne(tmp, document));
  }

  @Test
  void crdGetsOrder00(@TempDir Path tmp) throws IOException {
    final Map<String, Object> document =
        resource(
            "CustomResourceDefinition",
            "foos.example.com",
            null,
            "cluster-api",
            "operator",
            Map.of());

    assertEquals(
        "cluster-api/operator/00-customresourcedefinition-foos.example.com.yml",
        explodeOne(tmp, document));
  }

  private static String explodeOne(final Path tmp, final Map<String, Object> document)
      throws IOException {
    final YamlMapper yaml = new YamlMapper();
    final Path consolidated = tmp.resolve("synth.yaml");
    final Path target = tmp.resolve("exploded");
    yaml.write(consolidated).documents(List.of(document));

    final ManifestExplodeResult result =
        new DefaultManifestExplodeService(yaml)
            .explode(new ManifestExplodeRequest(consolidated, target));

    assertEquals(1, result.writtenFileCount());
    return target.relativize(result.writtenFiles().get(0)).toString();
  }

  private static Map<String, Object> resource(
      final String kind,
      final String name,
      final String namespace,
      final String domain,
      final String pkg,
      final Map<String, String> extraAnnotations) {
    final LinkedHashMap<String, String> annotations = new LinkedHashMap<>();
    annotations.put(ManifestAnnotations.DOMAIN, domain);
    annotations.put(ManifestAnnotations.PACKAGE, pkg);
    annotations.putAll(extraAnnotations);

    final LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("name", name);
    if (namespace != null) {
      metadata.put("namespace", namespace);
    }
    metadata.put("annotations", annotations);

    final LinkedHashMap<String, Object> document = new LinkedHashMap<>();
    document.put("apiVersion", "v1");
    document.put("kind", kind);
    document.put("metadata", metadata);
    return document;
  }
}
