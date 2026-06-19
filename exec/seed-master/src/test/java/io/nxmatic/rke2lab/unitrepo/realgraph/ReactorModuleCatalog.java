package io.nxmatic.rke2lab.unitrepo.realgraph;

import io.nxmatic.rke2lab.unitrepo.core.UnitResource;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The coarse (module) layer of the real-graph universe: one {@link UnitResource} per reactor module
 * in seed-master's closure, with module-to-module edges, feeding {@link RealGraphResolutionTest}.
 *
 * @deprecated This fixture transcribes reactor module ids BY HAND, so it is a duplicated source of
 *     truth that drifts silently from the real poms. The {@code -core}/{@code -port} split already
 *     left ids here pointing at modules that no longer exist ({@code systemd-contract} → now {@code
 *     systemd-port}; the un-split {@code manifests}/{@code netplan} → now {@code -core}/{@code
 *     -port}), and nothing catches it because the proof only checks the fixture against itself. The
 *     ids are deliberately NOT re-synced: this whole real-graph fixture proof is superseded once
 *     Felix boots for real and resolves actually-installed bundles (R4 boot seam), at which point
 *     this {@code realgraph} package is deleted, not repaired. See {@link RealGraphResolutionTest}.
 */
@Deprecated(forRemoval = true)
final class ReactorModuleCatalog {

  static final String NS_MODULE = "unitrepo.module";

  private final Map<String, UnitResource> byId = new LinkedHashMap<>();

  ReactorModuleCatalog() {
    // leaves first (no rke2lab deps)
    module("netplan");
    module("systemd-contract");
    module("cdk8s-systemd");
    module("incus");
    module("pulumi-automation-ext");
    // modules with edges
    module("pulumi-automation-ext-testkit", "pulumi-automation-ext");
    module("manifests", "cdk8s-systemd", "netplan");
    module(
        "seed-master",
        "incus",
        "manifests",
        "netplan",
        "pulumi-automation-ext",
        "pulumi-automation-ext-testkit",
        "systemd-contract");
  }

  private void module(String id, String... dependsOnModuleIds) {
    UnitResource unit = new UnitResource(id).provide(NS_MODULE, Map.of("module", id));
    for (String dep : dependsOnModuleIds) {
      unit.require(NS_MODULE, "(module=" + dep + ")");
    }
    byId.put(id, unit);
  }

  Map<String, UnitResource> byId() {
    return Map.copyOf(byId);
  }
}
