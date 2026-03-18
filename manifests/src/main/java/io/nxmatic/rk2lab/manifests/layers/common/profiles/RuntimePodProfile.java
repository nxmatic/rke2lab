// @codebase
package io.nxmatic.rk2lab.manifests.layers.common.profiles;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RuntimePodProfile {

  private final String runtimeClassName;

  public RuntimePodProfile(final String runtimeClassName) {
    this.runtimeClassName = runtimeClassName;
  }

  public Map<String, Object> apply(
      final List<Object> containers,
      final List<Object> volumes,
      final String serviceAccountName,
      final Map<String, Object> extraPodSpec) {
    LinkedHashMap<String, Object> podSpec = new LinkedHashMap<>();
    podSpec.put("containers", containers);
    podSpec.put("dnsPolicy", "Default");
    podSpec.put("hostNetwork", true);
    podSpec.put("runtimeClassName", runtimeClassName);
    podSpec.put("securityContext", Map.of());
    podSpec.put("serviceAccountName", serviceAccountName);
    podSpec.put("shareProcessNamespace", true);
    podSpec.put("tolerations", List.of(Map.of("operator", "Exists")));
    podSpec.put("volumes", volumes);
    podSpec.putAll(extraPodSpec);
    return Map.copyOf(podSpec);
  }
}
