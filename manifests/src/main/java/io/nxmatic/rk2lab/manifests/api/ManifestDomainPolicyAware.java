package io.nxmatic.rk2lab.manifests.api;

import java.util.Optional;

/** Marker contract for models that explicitly carry manifest-domain policy. */
public interface ManifestDomainPolicyAware {

  Optional<ManifestDomainPolicy> manifestDomainPolicy();

  default boolean hasManifestDomainPolicy() {
    return manifestDomainPolicy().isPresent();
  }
}
