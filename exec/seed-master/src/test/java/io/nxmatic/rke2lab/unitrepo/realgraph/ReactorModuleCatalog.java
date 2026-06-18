package io.nxmatic.rke2lab.unitrepo.realgraph;

import io.nxmatic.rke2lab.unitrepo.core.UnitResource;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The coarse (module) layer of the real-graph universe: one {@link UnitResource} per reactor module
 * that participates in seed-master's closure, with module-to-module edges transcribed faithfully
 * from the reactor poms (verified 2026-06-15). Same hardcoding discipline as {@code
 * ManifestDomainCatalog}. Test-scope only — the proof reads structure already in the build, it does
 * not introspect Maven at runtime.
 */
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
