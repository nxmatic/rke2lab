package io.nxmatic.rke2lab.controlplane.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConfigLoaderTest {

  private static ConfigLoader loaderOf(Map<String, Map<String, Object>> sections) {
    return ConfigLoader.of(section -> Optional.ofNullable(sections.get(section)));
  }

  @Test
  void optional_empty_when_section_or_key_absent() {
    final ConfigLoader loader = loaderOf(Map.of());
    assertTrue(loader.optional("incus", "project").isEmpty());
    assertTrue(loader.optionalPath("kubeconfig", "ref").isEmpty());
  }

  @Test
  void optional_returns_present_value() {
    final ConfigLoader loader = loaderOf(Map.of("incus", Map.of("project", "rke2lab")));
    assertEquals(Optional.of("rke2lab"), loader.optional("incus", "project"));
  }

  @Test
  void require_accumulates_without_throwing_mid_load() {
    final ConfigLoader loader = loaderOf(Map.of());
    loader.requirePath("incus", "configDir");
    loader.requirePath("worktree", "dir");
    assertEquals(List.of("incus.configDir", "worktree.dir"), loader.missingKeys());
  }

  @Test
  void diagnose_throws_with_all_keys() {
    final ConfigLoader loader = loaderOf(Map.of());
    loader.requirePath("incus", "configDir");
    loader.requirePath("image", "sharedFolder");
    final MissingRequiredConfiguration ex =
        assertThrows(MissingRequiredConfiguration.class, loader::diagnoseIfIncomplete);
    assertEquals(List.of("incus.configDir", "image.sharedFolder"), ex.keys());
  }

  @Test
  void require_present_value_not_accumulated() {
    final ConfigLoader loader =
        loaderOf(Map.of("worktree", Map.of("dir", "/private/var/lib/git/nxmatic/rke2lab")));
    assertEquals(
        Path.of("/private/var/lib/git/nxmatic/rke2lab"), loader.requirePath("worktree", "dir"));
    assertTrue(loader.missingKeys().isEmpty());
  }

  @Test
  void dotted_section_walks_into_submap() {
    final ConfigLoader loader =
        ConfigLoader.ofNestedRoot(Map.of("policy", Map.of("link", Map.of("gitops", "true"))));
    assertEquals(Optional.of(true), loader.optionalBoolean("policy.link", "gitops"));
  }
}
