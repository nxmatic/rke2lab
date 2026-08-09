package io.nxmatic.rke2lab.controlplane.config;

import static io.nxmatic.rke2lab.controlplane.config.OperatorConfiguration.loaderOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    loader.requirePath("incus", "remoteAddress");
    assertEquals(List.of("incus.configDir", "incus.remoteAddress"), loader.missingKeys());
  }

  @Test
  void diagnose_throws_with_all_keys() {
    final ConfigLoader loader = loaderOf(Map.of());
    loader.requirePath("incus", "configDir");
    loader.requirePath("incus", "remoteAddress");
    final MissingRequiredConfiguration ex =
        assertThrows(MissingRequiredConfiguration.class, loader::diagnoseIfIncomplete);
    assertEquals(List.of("incus.configDir", "incus.remoteAddress"), ex.keys());
  }

  @Test
  void dotted_section_walks_into_submap() {
    final ConfigLoader loader =
        ConfigLoader.ofNestedRoot(Map.of("manifests", Map.of("publish", Map.of("gitops", "true"))));
    assertEquals(Optional.of(true), loader.optionalBoolean("manifests.publish", "gitops"));
  }

  // --- bind(): a record's @SecretJoin deep-merges the named .secrets subtree before mapping ---

  @Test
  void bind_merges_the_secret_subtree_named_by_the_records_secret_join() {
    final ConfigLoader loader =
        ConfigLoader.ofNestedRoots(
            Map.of("bbox", Map.of("reconcile", Map.of("failOnError", "true"))),
            Map.of(
                "lan",
                Map.of("bbox", Map.of("uri", "https://mabbox.bytel.fr", "password", "s3cr3t"))));
    final Rke2labConfig.BboxConfig bbox = loader.bind(Rke2labConfig.BboxConfig.class, "bbox");
    // The typed input is mapped...
    assertEquals(Optional.of(true), bbox.reconcile().failOnError());
    // ...the router contact the host owns lands blind in the remainder (never named as fields)...
    assertEquals("https://mabbox.bytel.fr", bbox.rest().get("uri"));
    assertEquals("s3cr3t", bbox.rest().get("password"));
    // ...and the facet re-serialises the whole payload (reconcile rides along, no join meta).
    final String facet = bbox.facetJson();
    assertTrue(facet.contains("password"));
    assertTrue(facet.contains("reconcile"));
    assertTrue(!facet.contains("\"from\""));
  }

  @Test
  void bind_secret_leaf_wins_on_collision_with_config() {
    final ConfigLoader loader =
        ConfigLoader.ofNestedRoots(
            Map.of("bbox", Map.of("uri", "https://placeholder")),
            Map.of("lan", Map.of("bbox", Map.of("uri", "https://mabbox.bytel.fr"))));
    final Rke2labConfig.BboxConfig bbox = loader.bind(Rke2labConfig.BboxConfig.class, "bbox");
    assertEquals("https://mabbox.bytel.fr", bbox.rest().get("uri"));
  }

  @Test
  void bind_pulls_no_secret_for_a_record_without_a_secret_join() {
    final ConfigLoader loader =
        ConfigLoader.ofNestedRoots(
            Map.of("manifests", Map.of("publish", Map.of("gitops", "true"))),
            Map.of("lan", Map.of("bbox", Map.of("password", "s3cr3t"))));
    final Rke2labConfig.ManifestsConfig manifests =
        loader.bind(Rke2labConfig.ManifestsConfig.class, "manifests");
    // The manifests subtree is carried blind...
    assertTrue(manifests.rest().containsKey("publish"));
    // ...and with no @SecretJoin the secrets document contributes nothing.
    assertTrue(!manifests.facetJson().contains("password"));
  }
}
