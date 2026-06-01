package io.nxmatic.rk2lab.manifests.layers.platform;

import io.nxmatic.rk2lab.manifests.api.ManifestDomainCatalog;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomain;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomainRegistrar;
import io.nxmatic.rk2lab.manifests.systemd.SystemdUnitSynthesizer;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdChart;
import java.util.List;

/**
 * Platform domain registrar for foundational cluster services.
 *
 * <p>The platform domain houses cluster-wide infrastructure services that:
 *
 * <ul>
 *   <li>Deploy to kube-system namespace (RKE2-managed via HelmChart CRs)
 *   <li>Provide foundational capabilities other domains depend on
 *   <li>Run cluster-wide (not namespaced to specific concerns)
 * </ul>
 *
 * <p>Current platform services:
 *
 * <ul>
 *   <li><b>cert-manager</b>: Certificate provisioning via cert-manager.io (webhook certs for CAPI,
 *       Tekton operators)
 *   <li><b>kubernetes-replicator</b>: Cross-namespace secret/configmap replication for shared
 *       credentials
 *   <li><b>traefik</b>: (Future) Ingress controller override for RKE2's default
 * </ul>
 *
 * <p>Distinguished from domain-specific infrastructure (flux-operator in gitops, cilium-config in
 * networking) that serves a narrower concern.
 */
public final class PlatformDomainRegistrar implements LayerDomainRegistrar {

  private static final ManifestDomainCatalog CATALOG =
      ManifestDomainCatalog.builder().addDefaultDomains().addDefaultStageALinkableDomains().build();

  @Override
  public LayerDomain domain() {
    return new LayerDomain(
        CATALOG.platform(),
        List.of(),
        List.of(new CertManagerManifestUnit(), new ReplicatorManifestUnit())) {
      @Override
      public void synthesizeSystemdUnits(
          SystemdChart systemdChart,
          io.nxmatic.rk2lab.manifests.layers.common.SystemdSynthesisContext context) {
        super.synthesizeSystemdUnits(systemdChart, context);
        var synthesizer =
            new SystemdUnitSynthesizer(systemdChart, context.domainCatalog().platform(), context);
        synthesizer.manifestInstaller();
      }
    };
  }
}
