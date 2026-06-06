package io.nxmatic.rke2lab.controlplane.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class Rke2labConfigTest {

  private static ConfigLoader loaderOf(Map<String, Map<String, Object>> sections) {
    return ConfigLoader.of(section -> Optional.ofNullable(sections.get(section)));
  }

  private static Map<String, Map<String, Object>> full() {
    final Map<String, Map<String, Object>> sections = new HashMap<>();
    sections.put("cluster", Map.of("name", "bioskop"));
    sections.put(
        "incus", Map.of("project", "rke2lab", "configDir", "/Users/nxmatic/.config/incus"));
    sections.put("image", Map.of("sharedFolder", "/srv/distrobuilder"));
    sections.put("worktree", Map.of("dir", "/private/var/lib/git/nxmatic/rke2lab"));
    sections.put("systemd", Map.of("dbusHost", "bioskop-master", "dbusPort", "12434"));
    return sections;
  }

  @Test
  void populates_infra_and_cross_cutting() {
    final Rke2labConfig config = Rke2labConfig.from(loaderOf(full()));
    assertEquals(Optional.of("bioskop"), config.cluster().name());
    assertEquals(Optional.of("rke2lab"), config.incus().project());
    assertEquals(Path.of("/Users/nxmatic/.config/incus"), config.incus().configDir());
    assertEquals(Path.of("/srv/distrobuilder"), config.image().sharedFolder());
    assertEquals(Path.of("/private/var/lib/git/nxmatic/rke2lab"), config.worktree().dir());
    assertEquals(Optional.of("bioskop-master"), config.systemd().dbusHost());
    assertEquals(Optional.of(12434), config.systemd().dbusPort());
  }

  @Test
  void omitted_optional_is_empty() {
    final Rke2labConfig config = Rke2labConfig.from(loaderOf(full()));
    assertTrue(config.incus().defaultRemote().isEmpty());
    assertTrue(config.network().lanBridgeParent().isEmpty());
    assertTrue(config.kubeconfig().ref().isEmpty());
  }

  @Test
  void single_missing_mandatory_reported_by_name() {
    final Map<String, Map<String, Object>> sections = new HashMap<>(full());
    sections.put("incus", Map.of("project", "rke2lab")); // configDir omitted
    final MissingRequiredConfiguration ex =
        assertThrows(
            MissingRequiredConfiguration.class, () -> Rke2labConfig.from(loaderOf(sections)));
    assertEquals(List.of("incus.configDir"), ex.keys());
  }

  @Test
  void multiple_missing_mandatory_reported_together() {
    // Empty config: all three mandatory keys absent, in InfraDomain.values() order.
    final MissingRequiredConfiguration ex =
        assertThrows(
            MissingRequiredConfiguration.class, () -> Rke2labConfig.from(loaderOf(Map.of())));
    assertEquals(List.of("incus.configDir", "image.sharedFolder", "worktree.dir"), ex.keys());
  }

  @Test
  void defaults_path_does_not_validate_mandatory() {
    // Offline path: empty config must NOT throw.
    final Rke2labConfig config = Rke2labConfig.defaults();
    assertTrue(config.cluster().name().isEmpty());
  }
}
