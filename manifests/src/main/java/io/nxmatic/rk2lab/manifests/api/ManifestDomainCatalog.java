package io.nxmatic.rk2lab.manifests.api;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Canonical manifest-domain catalog shared across synthesis and controlplane policy. */
public final class ManifestDomainCatalog {

  private final List<String> all;
  private final List<String> stageALinkable;

  private ManifestDomainCatalog(Builder builder) {
    this.all = List.copyOf(builder.allDomains);
    this.stageALinkable = List.copyOf(builder.stageALinkableDomains);
  }

  public static Builder builder() {
    return new Builder();
  }

  public String cluster() {
    return "cluster";
  }

  public String storage() {
    return "storage";
  }

  public String replication() {
    return "replication";
  }

  public String gitops() {
    return "gitops";
  }

  public String runtime() {
    return "runtime";
  }

  public String networking() {
    return "networking";
  }

  public String mesh() {
    return "mesh";
  }

  public String highAvailability() {
    return "high-availability";
  }

  public String cicd() {
    return "cicd";
  }

  public String clusterApi() {
    return "cluster-api";
  }

  public String porch() {
    return "porch";
  }

  public List<String> all() {
    return all;
  }

  public List<String> stageALinkableDomains() {
    return stageALinkable;
  }

  public boolean isKnownDomainId(String domainId) {
    return all.contains(normalize(domainId));
  }

  public static final class Builder {
    private final LinkedHashSet<String> allDomains = new LinkedHashSet<>();
    private final LinkedHashSet<String> stageALinkableDomains = new LinkedHashSet<>();

    private Builder() {}

    public Builder addDefaultDomains() {
      return addAllDomains(
          List.of(
              "cluster",
              "storage",
              "replication",
              "gitops",
              "runtime",
              "networking",
              "mesh",
              "high-availability",
              "cicd",
              "cluster-api",
              "porch"));
    }

    public Builder addDefaultStageALinkableDomains() {
      return addStageALinkableDomains(
          List.of(
              "high-availability", "networking", "replication", "storage", "mesh", "cluster-api"));
    }

    public Builder addDomain(String domainId) {
      allDomains.add(normalizeNonBlank(domainId, "domainId"));
      return this;
    }

    public Builder addAllDomains(Iterable<String> domainIds) {
      Objects.requireNonNull(domainIds, "domainIds");
      for (String domainId : domainIds) {
        addDomain(domainId);
      }
      return this;
    }

    public Builder addStageALinkableDomain(String domainId) {
      stageALinkableDomains.add(normalizeNonBlank(domainId, "domainId"));
      return this;
    }

    public Builder addStageALinkableDomains(Iterable<String> domainIds) {
      Objects.requireNonNull(domainIds, "domainIds");
      for (String domainId : domainIds) {
        addStageALinkableDomain(domainId);
      }
      return this;
    }

    public Builder clearDomains() {
      allDomains.clear();
      return this;
    }

    public Builder clearStageALinkableDomains() {
      stageALinkableDomains.clear();
      return this;
    }

    public ManifestDomainCatalog build() {
      for (String domainId : stageALinkableDomains) {
        if (!allDomains.contains(domainId)) {
          throw new IllegalArgumentException(
              "Stage A linkable domain is not registered in catalog: " + domainId);
        }
      }
      if (allDomains.isEmpty()) {
        throw new IllegalArgumentException(
            "Manifest domain catalog must contain at least one domain");
      }
      return new ManifestDomainCatalog(this);
    }
  }

  private static String normalizeNonBlank(String value, String fieldName) {
    final String normalized = normalize(value);
    if (normalized.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return normalized;
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim();
  }
}
