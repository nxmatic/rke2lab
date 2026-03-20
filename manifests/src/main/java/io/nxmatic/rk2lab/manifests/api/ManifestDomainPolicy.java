package io.nxmatic.rk2lab.manifests.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical manifest-domain policy shared between cdk8s synthesis concepts and controlplane
 * activation logic.
 */
public record ManifestDomainPolicy(Map<String, Boolean> enabledByDomainId) {

  public ManifestDomainPolicy {
    enabledByDomainId = Map.copyOf(new LinkedHashMap<>(enabledByDomainId));
  }

  public static ManifestDomainPolicy stageALinkPolicy(
      boolean haEnabled,
      boolean networkingEnabled,
      boolean replicationEnabled,
      boolean storageEnabled,
      boolean meshEnabled) {
    return new ManifestDomainPolicy(
        Map.of(
            ManifestDomainIds.HA, haEnabled,
            ManifestDomainIds.NETWORKING, networkingEnabled,
            ManifestDomainIds.REPLICATION, replicationEnabled,
            ManifestDomainIds.STORAGE, storageEnabled,
            ManifestDomainIds.MESH, meshEnabled));
  }

  public static ManifestDomainPolicy enableOnly(Iterable<String> enabledDomainIds) {
    final LinkedHashMap<String, Boolean> flags = new LinkedHashMap<>();
    for (String domainId : ManifestDomainIds.all()) {
      flags.put(domainId, false);
    }
    for (String domainId : enabledDomainIds) {
      final String normalizedDomainId = domainId == null ? "" : domainId.trim();
      if (normalizedDomainId.isBlank()) {
        continue;
      }
      if (!flags.containsKey(normalizedDomainId)) {
        throw new IllegalArgumentException(
            "Unknown manifest domain id in policy: " + normalizedDomainId);
      }
      flags.put(normalizedDomainId, true);
    }
    return new ManifestDomainPolicy(flags);
  }

  public boolean isEnabled(String domainId) {
    return enabledByDomainId.getOrDefault(domainId, false);
  }

  public List<String> enabledDomainIds() {
    return enabledByDomainId.entrySet().stream()
        .filter(Map.Entry::getValue)
        .map(Map.Entry::getKey)
        .sorted()
        .toList();
  }

  public List<String> domainIds() {
    return enabledByDomainId.keySet().stream().sorted().toList();
  }

  public Map<String, Boolean> asMap() {
    return enabledByDomainId;
  }
}
