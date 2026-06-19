package io.nxmatic.rke2lab.controlplane.incus;

import io.nxmatic.rke2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rke2lab.manifests.bridge.ManifestDomainCatalog;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

/**
 * Host slot manifest describing the contents of a host asset directory ({@code host/}, {@code
 * host.staging.N/}, {@code host.backup.N/}).
 *
 * <p>Written as a Kubernetes ConfigMap so it can be:
 *
 * <ul>
 *   <li>Synced to {@code /srv/host/.rke2lab-manifest.yaml} on the node
 *   <li>Mounted into pods that need to discover available resources
 *   <li>Read by the flox NRI plugin to validate environments
 *   <li>Referenced from operator's {@code .local.d/} for slot selection
 * </ul>
 *
 * <p>Uses CDK8s for structured authoring (not string templates).
 */
public final class HostSlotManifest extends Construct {

  private final ApiObject configMap;

  private HostSlotManifest(
      Construct scope,
      String id,
      SlotType slotType,
      Integer slotSequence,
      Instant timestamp,
      String buildId,
      GitInfo git,
      PolicyInfo policy,
      List<FloxEnvironment> floxEnvironments,
      List<StagedManifest> stagedManifests,
      PromotionInfo promotion,
      SourceInfo source) {

    super(scope, id);

    final Map<String, Object> data = new LinkedHashMap<>();

    // Metadata section
    data.put("slotType", slotType.toYamlValue());
    if (slotSequence != null) {
      data.put("slotSequence", slotSequence);
    }
    data.put("timestamp", timestamp.toString());
    data.put("buildId", buildId);

    // Git section
    if (git != null) {
      data.put("git", gitToMap(git));
    }

    // Policy section
    if (policy != null) {
      data.put("policy", policyToMap(policy));
    }

    // Flox environments
    if (!floxEnvironments.isEmpty()) {
      data.put("floxEnvironments", floxEnvironmentsToList(floxEnvironments));
    }

    // Staged manifests (post-cluster resources)
    if (!stagedManifests.isEmpty()) {
      data.put("stagedManifests", stagedManifestsToList(stagedManifests));
    }

    // Promotion tracking
    if (promotion != null) {
      data.put("promotion", promotionToMap(promotion));
    }

    // Source provenance
    if (source != null) {
      data.put("source", sourceToMap(source));
    }

    // Create ConfigMap with manifest data as YAML-formatted string
    this.configMap =
        new ApiObject(
            this,
            "configmap",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ConfigMap")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("rke2lab-host-slot-manifest")
                        .namespace("rke2lab-system")
                        .labels(
                            Map.of(
                                "app.kubernetes.io/name",
                                "rke2lab-host-slot-manifest",
                                "app.kubernetes.io/component",
                                "host-metadata",
                                "rke2lab.io/slot-type",
                                slotType.toYamlValue(),
                                "rke2lab.io/build-id",
                                buildId))
                        .build())
                .build());

    this.configMap.addJsonPatch(JsonPatch.add("/data", Map.of("manifest.yaml", renderYaml(data))));
  }

  public ApiObject getConfigMap() {
    return configMap;
  }

  private static String renderYaml(Map<String, Object> data) {
    // Simple YAML rendering - CDK8s will handle proper formatting
    final var sb = new StringBuilder();
    for (var entry : data.entrySet()) {
      renderYamlEntry(sb, entry.getKey(), entry.getValue(), 0);
    }
    return sb.toString();
  }

  private static void renderYamlEntry(StringBuilder sb, String key, Object value, int indent) {
    final String indentStr = "  ".repeat(indent);
    if (value instanceof Map<?, ?> map) {
      sb.append(indentStr).append(key).append(":\n");
      for (var entry : map.entrySet()) {
        renderYamlEntry(sb, entry.getKey().toString(), entry.getValue(), indent + 1);
      }
    } else if (value instanceof List<?> list) {
      sb.append(indentStr).append(key).append(":\n");
      for (var item : list) {
        if (item instanceof Map<?, ?>) {
          sb.append(indentStr).append("  - \n");
          @SuppressWarnings("unchecked")
          Map<String, Object> itemMap = (Map<String, Object>) item;
          for (var entry : itemMap.entrySet()) {
            renderYamlEntry(sb, entry.getKey(), entry.getValue(), indent + 2);
          }
        } else {
          sb.append(indentStr).append("  - ").append(item).append("\n");
        }
      }
    } else {
      sb.append(indentStr).append(key).append(": ").append(value).append("\n");
    }
  }

  private static Map<String, Object> gitToMap(GitInfo git) {
    final Map<String, Object> map = new LinkedHashMap<>();
    map.put("commit", git.commit);
    map.put("commitFull", git.commitFull);
    map.put("branch", git.branch);
    map.put("dirty", git.dirty);
    map.put("commitMessage", git.commitMessage);
    map.put("author", git.author);
    map.put("commitDate", git.commitDate);
    return map;
  }

  private static Map<String, Object> policyToMap(PolicyInfo policy) {
    final Map<String, Object> map = new LinkedHashMap<>();
    map.put("manifestDomain", policy.manifestDomain);
    map.put("debug", policy.debug);
    return map;
  }

  private static List<Map<String, Object>> floxEnvironmentsToList(List<FloxEnvironment> envs) {
    return envs.stream()
        .map(
            env -> {
              final Map<String, Object> map = new LinkedHashMap<>();
              map.put("category", env.category);
              map.put("name", env.name);
              map.put("hasManifest", env.hasManifest);
              return map;
            })
        .toList();
  }

  private static List<Map<String, Object>> stagedManifestsToList(List<StagedManifest> manifests) {
    return manifests.stream()
        .map(
            manifest -> {
              final Map<String, Object> map = new LinkedHashMap<>();
              map.put("domain", manifest.domain);
              map.put("subpath", manifest.subpath);
              map.put("description", manifest.description);
              return map;
            })
        .toList();
  }

  private static Map<String, Object> promotionToMap(PromotionInfo promotion) {
    final Map<String, Object> map = new LinkedHashMap<>();
    map.put("promotedToActive", promotion.promotedToActive);
    if (promotion.promotedAt != null) {
      map.put("promotedAt", promotion.promotedAt.toString());
    }
    if (promotion.previousActiveManifest != null) {
      map.put("previousActiveManifest", promotion.previousActiveManifest);
    }
    return map;
  }

  private static Map<String, Object> sourceToMap(SourceInfo source) {
    final Map<String, Object> map = new LinkedHashMap<>();
    map.put("type", source.type.toYamlValue());
    map.put("scratchPath", source.scratchPath);
    if (source.parentSlot != null) {
      map.put("parentSlot", source.parentSlot);
    }
    return map;
  }

  public enum SlotType {
    STAGING("staging"),
    BACKUP("backup"),
    ACTIVE("active");

    private final String yamlValue;

    SlotType(String yamlValue) {
      this.yamlValue = yamlValue;
    }

    public String toYamlValue() {
      return yamlValue;
    }
  }

  public record GitInfo(
      String commit,
      String commitFull,
      String branch,
      boolean dirty,
      String commitMessage,
      String author,
      String commitDate) {}

  public record PolicyInfo(Map<String, Boolean> manifestDomain, Map<String, Boolean> debug) {}

  public record FloxEnvironment(String category, String name, boolean hasManifest) {}

  public record StagedManifest(String domain, String subpath, String description) {}

  public record PromotionInfo(
      boolean promotedToActive, Instant promotedAt, String previousActiveManifest) {}

  public enum SourceType {
    FRESH_BUILD("fresh-build"),
    RESTORED_FROM_BACKUP("restored-from-backup"),
    ROLLED_BACK("rolled-back");

    private final String yamlValue;

    SourceType(String yamlValue) {
      this.yamlValue = yamlValue;
    }

    public String toYamlValue() {
      return yamlValue;
    }
  }

  public record SourceInfo(SourceType type, String scratchPath, String parentSlot) {}

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private SlotType slotType;
    private Integer slotSequence;
    private Instant timestamp;
    private String buildId;
    private GitInfo git;
    private PolicyInfo policy;
    private final List<FloxEnvironment> floxEnvironments = new java.util.ArrayList<>();
    private final List<StagedManifest> stagedManifests = new java.util.ArrayList<>();
    private PromotionInfo promotion;
    private SourceInfo source;

    private Builder() {}

    public Builder slotType(SlotType slotType) {
      this.slotType = slotType;
      return this;
    }

    public Builder slotSequence(int slotSequence) {
      this.slotSequence = slotSequence;
      return this;
    }

    public Builder timestamp(Instant timestamp) {
      this.timestamp = timestamp;
      return this;
    }

    public Builder buildId(String buildId) {
      this.buildId = buildId;
      return this;
    }

    public Builder gitCommit(String commit) {
      return gitInfo(commit, commit, "", false, "", "", "");
    }

    public Builder gitInfo(
        String commit,
        String commitFull,
        String branch,
        boolean dirty,
        String commitMessage,
        String author,
        String commitDate) {
      this.git = new GitInfo(commit, commitFull, branch, dirty, commitMessage, author, commitDate);
      return this;
    }

    public Builder policy(ControlplanePolicy policy) {
      if (policy == null) return this;

      final ManifestDomainCatalog catalog =
          ManifestDomainCatalog.builder()
              .addDefaultDomains()
              .addDefaultStageALinkableDomains()
              .build();

      final var manifestDomain = new LinkedHashMap<String, Boolean>();
      manifestDomain.put("cluster", policy.manifestLink().domains().isEnabled(catalog.cluster()));
      manifestDomain.put("storage", policy.manifestLink().domains().isEnabled(catalog.storage()));
      manifestDomain.put("gitops", policy.manifestLink().domains().isEnabled(catalog.gitops()));
      manifestDomain.put("runtime", policy.manifestLink().domains().isEnabled(catalog.runtime()));
      manifestDomain.put(
          "networking", policy.manifestLink().domains().isEnabled(catalog.networking()));
      manifestDomain.put("mesh", policy.manifestLink().domains().isEnabled(catalog.mesh()));
      manifestDomain.put(
          "highAvailability",
          policy.manifestLink().domains().isEnabled(catalog.highAvailability()));
      manifestDomain.put("cicd", policy.manifestLink().domains().isEnabled(catalog.cicd()));
      manifestDomain.put(
          "clusterApi", policy.manifestLink().domains().isEnabled(catalog.clusterApi()));
      manifestDomain.put("platform", policy.manifestLink().domains().isEnabled(catalog.platform()));

      final var debug = new LinkedHashMap<String, Boolean>();
      debug.put("mesh", policy.debug().mesh());
      debug.put("networking", policy.debug().networking());
      debug.put("nriPluginsFlox", policy.debug().nriPluginsFlox());

      this.policy = new PolicyInfo(manifestDomain, debug);
      return this;
    }

    public Builder addFloxEnvironment(String category, String name, boolean hasManifest) {
      this.floxEnvironments.add(new FloxEnvironment(category, name, hasManifest));
      return this;
    }

    public Builder addStagedManifest(String domain, String subpath, String description) {
      this.stagedManifests.add(new StagedManifest(domain, subpath, description));
      return this;
    }

    public Builder promotion(
        boolean promotedToActive, Instant promotedAt, String previousActiveManifest) {
      this.promotion = new PromotionInfo(promotedToActive, promotedAt, previousActiveManifest);
      return this;
    }

    public Builder source(SourceType type, String scratchPath, String parentSlot) {
      this.source = new SourceInfo(type, scratchPath, parentSlot);
      return this;
    }

    public HostSlotManifest build(Construct scope, String id) {
      return new HostSlotManifest(
          scope,
          id,
          slotType,
          slotSequence,
          timestamp != null ? timestamp : Instant.now(),
          buildId,
          git,
          policy,
          List.copyOf(floxEnvironments),
          List.copyOf(stagedManifests),
          promotion,
          source);
    }
  }
}
