package io.nxmatic.rk2lab.manifests.api;

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

  public static HostAssetDeliveryPolicy floxContainerdShim() {
    return new HostAssetDeliveryPolicy(
        "runtime/containerd-shim-flox",
        MANIFEST_DOMAIN_CATALOG.runtime(),
        "/srv/host/k8s-daemonset.d/runtime/containerd-shim-flox",
        HostAssetDeliveryBackend.DAEMONSET,
        HostAssetMaterializationMode.MIXED,
        true,
        false,
        true);
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
