package io.nxmatic.rke2lab.manifests.node;

import io.nxmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.nxmatic.rke2lab.manifests.contract.ManifestDomainPolicy;
import io.nxmatic.rke2lab.manifests.contract.node.NodeEnvContext;
import io.nxmatic.rke2lab.manifests.contract.node.NodeEnvContributor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.osgi.service.component.annotations.Component;

/**
 * Contributes the {@code publish} env section: one {@code
 * RKE2LAB_MANIFESTS_PUBLISH_<LAYER>_ENABLED} per publishable layer, read by the master's
 * install/ready scripts to decide which domain manifests to stow into RKE2's server-manifests
 * directory.
 *
 * <p>The publishable set is the catalog's {@code stageALinkableDomains} (the operator-toggleable
 * facet domains — base infra like {@code platform} is always-on, never a publish knob), so the
 * emission is a single source of truth: no hardcoded var list. The run-scoped decision arrives via
 * {@link NodeEnvContext#manifestDomainPolicy()} — the same {@link ManifestDomainPolicy} that drives
 * the synth-time domain filter — so this contributor makes the policy's role in the host env
 * explicit.
 */
@Component(service = NodeEnvContributor.class)
public class PublishNodeEnvContributor implements NodeEnvContributor {

  private static final ManifestDomainCatalog CATALOG =
      ManifestDomainCatalog.builder().addDefaultDomains().addDefaultStageALinkableDomains().build();

  @Override
  public String domainId() {
    return "publish";
  }

  @Override
  public List<String> contributedSections() {
    return List.of("publish");
  }

  @Override
  public Map<String, String> contributeVariables(String sectionName, NodeEnvContext context) {
    if (!"publish".equals(sectionName)) {
      return Map.of();
    }
    final ManifestDomainPolicy policy = context.manifestDomainPolicy();
    final Map<String, String> vars = new LinkedHashMap<>();
    for (String domainId : CATALOG.stageALinkableDomains()) {
      vars.put(publishVarName(domainId), Boolean.toString(policy.isEnabled(domainId)));
    }
    return Map.copyOf(vars);
  }

  /** {@code high-availability} → {@code RKE2LAB_MANIFESTS_PUBLISH_HIGH_AVAILABILITY_ENABLED}. */
  private static String publishVarName(String domainId) {
    return "RKE2LAB_MANIFESTS_PUBLISH_"
        + domainId.toUpperCase(Locale.ROOT).replace('-', '_')
        + "_ENABLED";
  }
}
