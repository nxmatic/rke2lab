// @codebase
package io.nxmatic.rk2lab.manifests.layers.runtime;

import io.nxmatic.rk2lab.manifests.layers.common.profiles.PackageMetadataProfile;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class RuntimeEnvConfigLayer extends Construct {

  public static final String LEGACY_PATH_PREFIX = "runtime/env-config/";

  private static final List<String> ENV_SECTIONS =
      List.of(
          "cilium",
          "cluster",
          "config",
          "containerd",
          "cri",
          "daemonset-script-policy",
          "etcdctl",
          "helm",
          "kpt",
          "kubectl",
          "network-cluster",
          "network-lan-wan",
          "network-node",
          "network-vip",
          "node",
          "paths",
          "rke2",
          "user");

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("runtime", "env-config");

  public RuntimeEnvConfigLayer(final Construct scope, final String id) {
    super(scope, id);

    for (String section : ENV_SECTIONS) {
      createSectionConfigMap(section);
    }
  }

  private void createSectionConfigMap(final String section) {
    final String cmName = "env-section-" + section;
    final Map<String, String> data = loadEnvData(section);

    ApiObject configMap =
        new ApiObject(
            this,
            "configmap-" + cmName,
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ConfigMap")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(cmName)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|ConfigMap|default|" + cmName,
                                Map.of(
                                    "config.kubernetes.io/local-config",
                                    "true",
                                    "description.kpt.dev",
                                    "Environment section " + section,
                                    "env.rk2lab.nxmatic.io/section",
                                    "section-" + section)))
                        .build())
                .build());

    configMap.addJsonPatch(JsonPatch.add("/data", data));
  }

  private Map<String, String> loadEnvData(final String section) {
    final String resourcePath = "/runtime/env-config/" + section + ".env";
    final InputStream input = RuntimeEnvConfigLayer.class.getResourceAsStream(resourcePath);
    if (input == null) {
      throw new IllegalStateException("Missing runtime env-config resource: " + resourcePath);
    }

    final LinkedHashMap<String, String> values = new LinkedHashMap<>();
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        final String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
          continue;
        }

        final int separator = line.indexOf('=');
        if (separator <= 0) {
          continue;
        }

        final String key = line.substring(0, separator).trim();
        final String value = line.substring(separator + 1).trim();
        if (!key.isBlank()) {
          values.put(key, value);
        }
      }
    } catch (IOException ex) {
      throw new IllegalStateException(
          "Failed reading runtime env-config resource: " + resourcePath, ex);
    }

    return Map.copyOf(values);
  }
}
