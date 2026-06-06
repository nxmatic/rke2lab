package io.nxmatic.rke2lab.controlplane.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.nxmatic.rke2lab.controlplane.incus.BootstrapConfig;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BootstrapConfigFromTest {

  private static Rke2labConfig dto(Map<String, Map<String, Object>> sections) {
    return Rke2labConfig.from(
        ConfigLoader.of(section -> Optional.ofNullable(sections.get(section))));
  }

  private static Map<String, Map<String, Object>> mandatoryOnly() {
    final Map<String, Map<String, Object>> sections = new HashMap<>();
    sections.put("incus", Map.of("configDir", "/Users/nxmatic/.config/incus"));
    sections.put("image", Map.of("sharedFolder", "/srv/distrobuilder"));
    sections.put("worktree", Map.of("dir", "/private/var/lib/git/nxmatic/rke2lab"));
    return sections;
  }

  @Test
  void mandatory_values_pass_through() {
    final BootstrapConfig boot = BootstrapConfig.from(dto(mandatoryOnly()));
    assertEquals(Path.of("/private/var/lib/git/nxmatic/rke2lab"), boot.worktreeDir());
    assertEquals(Path.of("/Users/nxmatic/.config/incus"), boot.incusConfigDir());
    assertEquals(Path.of("/srv/distrobuilder"), boot.imageSharedFolder());
  }

  @Test
  void omitted_optionals_get_defaults() {
    final BootstrapConfig boot = BootstrapConfig.from(dto(mandatoryOnly()));
    assertEquals("bioskop", boot.clusterName());
    assertEquals("master", boot.nodeName());
    assertEquals("rke2lab", boot.incusProject());
    assertEquals("bioskop-nixos", boot.incusDefaultRemote());
    assertEquals(URI.create("https://bioskop-nixos.local:8443"), boot.incusRemoteAddress());
    assertEquals("control-node", boot.imageAlias());
    assertEquals("bioskop-nixos.local", boot.imageBuilderHost());
    assertEquals("rke2lab", boot.profileName());
    assertEquals("lan-br", boot.lanBridgeParent());
    assertEquals("vmnet-br", boot.vmnetNetworkName());
    assertEquals(URI.create("https://10.66.106.10:6443"), boot.apiEndpoint());
    assertEquals(true, boot.nfsAutomount());
    assertEquals("bioskop-master", boot.systemdAdapterDbusHost());
    assertEquals(12434, boot.systemdAdapterDbusPort());
    assertEquals(3, boot.hostAssetRotationRetentionCount());
    assertEquals(Duration.ofMinutes(10), boot.readinessTimeout());
  }

  @Test
  void kubeconfig_ref_defaults_to_cluster_scoped_path() {
    final BootstrapConfig boot = BootstrapConfig.from(dto(mandatoryOnly()));
    assertEquals(Path.of(".local.d/bioskop/kubeconfig.yaml"), boot.kubeconfigRef());
  }
}
