package io.nxmatic.rke2lab.pulumi.automation;

import com.pulumi.automation.StackDeployment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Wraps a Pulumi {@link StackDeployment} and adds the ability to collect named outputs across all
 * resources in the deployment.
 *
 * <p>This is the neutral state object for reading a stack's deployment graph. Pulumi's {@code
 * StackDeployment} only exposes the raw deployment map; {@code StackSnapshot} adds {@code
 * outputsNamed(key)} to collect a specific output field from every resource that has it.
 */
public record StackSnapshot(StackDeployment wrapped) {

  public static StackSnapshot of(StackDeployment deployment) {
    return new StackSnapshot(deployment);
  }

  /**
   * @return deployment map, or empty if the wrapped deployment is null or absent.
   */
  public Optional<Map<String, Object>> deployment() {
    if (wrapped == null) {
      return Optional.empty();
    }
    Map<String, Object> deploymentMap = wrapped.deployment();
    return Optional.ofNullable(deploymentMap);
  }

  /**
   * Collects the value of a named output from every resource in the deployment that has it.
   *
   * <p>For each resource in {@code deployment().resources[]}, if the resource has {@code
   * outputs.<key>}, that value is collected. The returned list preserves resource order.
   *
   * @param key the output key to collect
   * @return list of output values, in resource order; empty list if the key is absent or the
   *     deployment is malformed; never throws
   */
  public List<Object> outputsNamed(String key) {
    Optional<Map<String, Object>> deploymentOpt = deployment();
    if (deploymentOpt.isEmpty()) {
      return Collections.emptyList();
    }

    Map<String, Object> deploymentMap = deploymentOpt.get();
    Object resourcesObj = deploymentMap.get("resources");
    if (!(resourcesObj instanceof List)) {
      return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    List<Object> resources = (List<Object>) resourcesObj;

    List<Object> collected = new ArrayList<>();
    for (Object resourceObj : resources) {
      if (!(resourceObj instanceof Map)) {
        continue;
      }

      @SuppressWarnings("unchecked")
      Map<String, Object> resource = (Map<String, Object>) resourceObj;

      Object outputsObj = resource.get("outputs");
      if (!(outputsObj instanceof Map)) {
        continue;
      }

      @SuppressWarnings("unchecked")
      Map<String, Object> outputs = (Map<String, Object>) outputsObj;

      if (outputs.containsKey(key)) {
        collected.add(outputs.get(key));
      }
    }

    return collected;
  }
}
