package io.seedmatic.rke2lab.host.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.seed.broker.port.SecretsGateway;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExecutionEnvironmentTest {

  @Test
  void operatorWhenNoKubernetesSignalAndNoOverride() {
    assertEquals(ExecutionEnclosure.OPERATOR, new ExecutionEnvironment(Map.of()).enclosure());
    assertFalse(new ExecutionEnvironment(Map.of()).enclosure().inCluster());
  }

  @Test
  void inClusterWhenKubeletSignalPresent() {
    final ExecutionEnvironment env =
        new ExecutionEnvironment(Map.of(ExecutionEnvironment.KUBERNETES_SIGNAL, "10.43.0.1"));
    assertEquals(ExecutionEnclosure.IN_CLUSTER, env.enclosure());
    assertTrue(env.enclosure().inCluster());
  }

  @Test
  void blankKubernetesSignalIsNotInCluster() {
    assertEquals(
        ExecutionEnclosure.OPERATOR,
        new ExecutionEnvironment(Map.of(ExecutionEnvironment.KUBERNETES_SIGNAL, "  ")).enclosure());
  }

  @Test
  void overrideWinsOverAutoDetection() {
    // k8s signal present, but the override pins OPERATOR
    assertEquals(
        ExecutionEnclosure.OPERATOR,
        new ExecutionEnvironment(
                Map.of(
                    ExecutionEnvironment.KUBERNETES_SIGNAL, "10.43.0.1",
                    ExecutionEnvironment.OVERRIDE_ENV, "operator"))
            .enclosure());
    // no k8s signal, but the override forces IN_CLUSTER (local exercise of the cluster path)
    assertEquals(
        ExecutionEnclosure.IN_CLUSTER,
        new ExecutionEnvironment(Map.of(ExecutionEnvironment.OVERRIDE_ENV, "in-cluster"))
            .enclosure());
  }

  @Test
  void unknownOverrideFailsLoud() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExecutionEnvironment(Map.of(ExecutionEnvironment.OVERRIDE_ENV, "banana"))
                .enclosure());
  }

  @Test
  void inClusterIsSecretBlind() {
    final SecretsGateway gw =
        new ExecutionEnvironment(Map.of(ExecutionEnvironment.KUBERNETES_SIGNAL, "10.43.0.1"))
            .secretsGateway();
    assertInstanceOf(EmptySecretsGateway.class, gw);
    assertTrue(gw.read("github").isEmpty());
    assertTrue(gw.read("kubernetes").isEmpty());
    assertTrue(gw.read("tailscale").isEmpty());
  }

  @Test
  void operatorComposesTheStandaloneChain() {
    assertInstanceOf(
        ChainedSecretsGateway.class, new ExecutionEnvironment(Map.of()).secretsGateway());
  }
}
