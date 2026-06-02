package io.nxmatic.rk2lab.manifests;

import java.util.Objects;

/** Shared policy model describing how a workflow delivers and materializes host assets. */
public record HostAssetDeliveryPolicy(
    String policyId,
    String ownerDomainId,
    String hostAssetRoot,
    HostAssetDeliveryBackend deliveryBackend,
    HostAssetMaterializationMode materializationMode,
    boolean verificationRequired,
    boolean rotateExistingRoot,
    boolean enabled) {

  private static final ManifestDomainCatalog MANIFEST_DOMAIN_CATALOG =
      ManifestDomainCatalog.builder().addDefaultDomains().addDefaultStageALinkableDomains().build();

  public HostAssetDeliveryPolicy {
    policyId = requireNonBlank(policyId, "policyId");
    ownerDomainId = requireKnownDomainId(ownerDomainId);
    hostAssetRoot = requireAbsoluteHostAssetRoot(hostAssetRoot);
    deliveryBackend = Objects.requireNonNull(deliveryBackend, "deliveryBackend");
    materializationMode = Objects.requireNonNull(materializationMode, "materializationMode");
    if (materializationMode == HostAssetMaterializationMode.ENCODED_ARCHIVE
        && !verificationRequired) {
      throw new IllegalArgumentException("Encoded archive materialization requires verification");
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private String policyId = "";
    private String ownerDomainId = "";
    private String hostAssetRoot = "";
    private HostAssetDeliveryBackend deliveryBackend = HostAssetDeliveryBackend.BOOTSTRAP;
    private HostAssetMaterializationMode materializationMode =
        HostAssetMaterializationMode.DIRECT_FILES;
    private boolean verificationRequired;
    private boolean rotateExistingRoot;
    private boolean enabled = true;

    private Builder() {}

    public Builder policyId(String value) {
      this.policyId = value;
      return this;
    }

    public Builder ownerDomainId(String value) {
      this.ownerDomainId = value;
      return this;
    }

    public Builder hostAssetRoot(String value) {
      this.hostAssetRoot = value;
      return this;
    }

    public Builder deliveryBackend(HostAssetDeliveryBackend value) {
      this.deliveryBackend = value;
      return this;
    }

    public Builder materializationMode(HostAssetMaterializationMode value) {
      this.materializationMode = value;
      return this;
    }

    public Builder verificationRequired(boolean value) {
      this.verificationRequired = value;
      return this;
    }

    public Builder rotateExistingRoot(boolean value) {
      this.rotateExistingRoot = value;
      return this;
    }

    public Builder enabled(boolean value) {
      this.enabled = value;
      return this;
    }

    public HostAssetDeliveryPolicy build() {
      return new HostAssetDeliveryPolicy(
          policyId,
          ownerDomainId,
          hostAssetRoot,
          deliveryBackend,
          materializationMode,
          verificationRequired,
          rotateExistingRoot,
          enabled);
    }
  }

  public boolean isOwnedBy(String domainId) {
    return ownerDomainId.equals(normalize(domainId));
  }

  public boolean usesDaemonsetDelivery() {
    return deliveryBackend == HostAssetDeliveryBackend.DAEMONSET;
  }

  public boolean materializesEncodedArchives() {
    return switch (materializationMode) {
      case DIRECT_FILES -> false;
      case ENCODED_ARCHIVE, MIXED -> true;
    };
  }

  public static HostAssetDeliveryPolicy floxRuntime() {
    return builder()
        .policyId("runtime/flox")
        .ownerDomainId(MANIFEST_DOMAIN_CATALOG.runtime())
        .hostAssetRoot("/srv/host/k8s-daemonset.d/runtime/flox")
        .deliveryBackend(HostAssetDeliveryBackend.DAEMONSET)
        .materializationMode(HostAssetMaterializationMode.MIXED)
        .verificationRequired(true)
        .rotateExistingRoot(false)
        .enabled(true)
        .build();
  }

  public static HostAssetDeliveryPolicy systemdLibexecPlaceholder() {
    return builder()
        .policyId("runtime/systemd-libexec-placeholder")
        .ownerDomainId(MANIFEST_DOMAIN_CATALOG.runtime())
        .hostAssetRoot("/srv/host/systemd-libexec.d")
        .deliveryBackend(HostAssetDeliveryBackend.BOOTSTRAP)
        .materializationMode(HostAssetMaterializationMode.DIRECT_FILES)
        .verificationRequired(false)
        .rotateExistingRoot(false)
        .enabled(false)
        .build();
  }

  private static String requireKnownDomainId(String ownerDomainId) {
    final String normalizedOwnerDomainId = requireNonBlank(ownerDomainId, "ownerDomainId");
    if (!MANIFEST_DOMAIN_CATALOG.isKnownDomainId(normalizedOwnerDomainId)) {
      throw new IllegalArgumentException(
          "Unknown host-asset owner domain id: " + normalizedOwnerDomainId);
    }
    return normalizedOwnerDomainId;
  }

  private static String requireAbsoluteHostAssetRoot(String hostAssetRoot) {
    final String normalizedHostAssetRoot = requireNonBlank(hostAssetRoot, "hostAssetRoot");
    if (!normalizedHostAssetRoot.startsWith("/")) {
      throw new IllegalArgumentException(
          "Host asset root must be an absolute path: " + normalizedHostAssetRoot);
    }
    return normalizedHostAssetRoot;
  }

  private static String requireNonBlank(String value, String fieldName) {
    final String normalizedValue = normalize(value);
    if (normalizedValue.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return normalizedValue;
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim();
  }
}
