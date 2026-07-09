package io.nxmatic.rke2lab.doctor.port;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.nxmatic.rke2lab.seed.broker.port.Checkpoint;
import org.junit.jupiter.api.Test;

/**
 * Pins the single-source join key: each checkpoint's resource name is DERIVED from its slug ({@code
 * "seed-" + slug}), so the BDD identity and the Pulumi resource name can never drift apart by hand
 * (the clusterApi/cluster-api silent-failure pattern).
 */
class CheckpointTest {

  @Test
  void cluster_readiness_exposes_its_slug_and_derived_resource_name() {
    assertEquals("cluster-readiness", Checkpoint.CLUSTER_READINESS.slug());
    assertEquals("seed-cluster-readiness", Checkpoint.CLUSTER_READINESS.resourceName());
  }

  @Test
  void systemd_adapter_exposes_its_slug_and_derived_resource_name() {
    assertEquals("systemd-adapter", Checkpoint.SYSTEMD_ADAPTER.slug());
    assertEquals("seed-systemd-adapter", Checkpoint.SYSTEMD_ADAPTER.resourceName());
  }

  @Test
  void cluster_readiness_exposes_its_scenario_title() {
    assertEquals("cluster becomes ready", Checkpoint.CLUSTER_READINESS.scenarioTitle());
  }

  @Test
  void systemd_adapter_exposes_its_scenario_title() {
    assertEquals("systemd adapter becomes reachable", Checkpoint.SYSTEMD_ADAPTER.scenarioTitle());
  }
}
