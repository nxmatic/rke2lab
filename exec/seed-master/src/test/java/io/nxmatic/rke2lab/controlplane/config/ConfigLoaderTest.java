package io.nxmatic.rke2lab.controlplane.config;

import static io.nxmatic.rke2lab.controlplane.config.OperatorConfiguration.loaderOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConfigLoaderTest {

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
        ConfigLoader.ofNestedRoot(Map.of("manifests", Map.of("publish", Map.of("gitops", "true"))));
    assertEquals(Optional.of(true), loader.optionalBoolean("manifests.publish", "gitops"));
  }

  // --- two-file join: a coordinate's `secret:` meta pulls the named .secrets subtree in ---

  @Test
  void secret_meta_merges_named_secrets_subtree_and_strips_the_meta() {
    final ConfigLoader loader =
        ConfigLoader.ofNestedRoots(
            Map.of(
                "bbox",
                Map.of(
                    "reconcile",
                    Map.of("failOnError", "true"),
                    "secret",
                    Map.of("from", "lan.bbox", "role", "FACET"))),
            Map.of(
                "lan",
                Map.of("bbox", Map.of("uri", "https://mabbox.bytel.fr", "password", "s3cr3t"))));
    // The named .secrets subtree is joined into the coordinate...
    assertEquals(Optional.of("https://mabbox.bytel.fr"), loader.optional("bbox", "uri"));
    assertEquals(Optional.of("s3cr3t"), loader.optional("bbox", "password"));
    // ...the pre-existing config leaf survives...
    assertEquals(Optional.of(true), loader.optionalBoolean("bbox.reconcile", "failOnError"));
    // ...and the join instruction itself is stripped from the merged view.
    assertTrue(loader.optional("bbox", "secret").isEmpty());
    assertTrue(loader.subtreeJson("bbox").contains("password"));
    assertTrue(!loader.subtreeJson("bbox").contains("\"from\""));
  }

  @Test
  void secret_leaf_wins_on_collision_with_config() {
    final ConfigLoader loader =
        ConfigLoader.ofNestedRoots(
            Map.of(
                "bbox", Map.of("uri", "https://placeholder", "secret", Map.of("from", "lan.bbox"))),
            Map.of("lan", Map.of("bbox", Map.of("uri", "https://mabbox.bytel.fr"))));
    assertEquals(Optional.of("https://mabbox.bytel.fr"), loader.optional("bbox", "uri"));
  }

  @Test
  void a_coordinate_without_a_secret_meta_is_untouched() {
    final ConfigLoader loader =
        ConfigLoader.ofNestedRoots(
            Map.of("manifests", Map.of("publish", Map.of("gitops", "true"))),
            Map.of("lan", Map.of("bbox", Map.of("password", "s3cr3t"))));
    assertEquals(Optional.of(true), loader.optionalBoolean("manifests.publish", "gitops"));
    // No secret: meta on manifests → the secrets document contributes nothing to it.
    assertTrue(loader.optional("manifests", "password").isEmpty());
  }
}
