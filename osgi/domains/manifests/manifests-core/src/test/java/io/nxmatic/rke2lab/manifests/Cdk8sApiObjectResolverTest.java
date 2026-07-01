package io.nxmatic.rke2lab.manifests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.manifests.refs.ConfigMapRef;
import io.nxmatic.rke2lab.manifests.refs.NamespaceRef;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.App;
import org.cdk8s.AppProps;
import org.cdk8s.Chart;
import org.junit.jupiter.api.Test;

/** Tests for cdk8s-tree-based ApiObject resolution. */
class Cdk8sApiObjectResolverTest {

  @Test
  void requireFindsNamespaceByKindAndName() {
    App app = new App(AppProps.builder().outdir("/tmp/cdk8s-resolver-test").build());
    Chart chart = new Chart(app, "test");
    Cdk8sApiObjectResolver resolver = new Cdk8sApiObjectResolver(chart);

    NamespaceRef ref = NamespaceRef.of("cluster/runtime-system-namespace", "rke2lab-system");
    ApiObject namespace =
        new ApiObject(
            chart,
            "namespace-rke2lab-system",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Namespace")
                .metadata(ApiObjectMetadata.builder().name("rke2lab-system").build())
                .build());

    ApiObject found = resolver.require(ref);
    assertSame(namespace, found);
    assertEquals("Namespace", found.getKind());
    assertEquals("rke2lab-system", found.getName());
  }

  @Test
  void requireFindsConfigMapByKindNamespaceAndName() {
    App app = new App(AppProps.builder().outdir("/tmp/cdk8s-resolver-test").build());
    Chart chart = new Chart(app, "test");
    Cdk8sApiObjectResolver resolver = new Cdk8sApiObjectResolver(chart);

    NamespaceRef nsRef = NamespaceRef.of("cluster/runtime-system-namespace", "rke2lab-system");
    new ApiObject(
        chart,
        "namespace-rke2lab-system",
        ApiObjectProps.builder()
            .apiVersion("v1")
            .kind("Namespace")
            .metadata(ApiObjectMetadata.builder().name("rke2lab-system").build())
            .build());

    ConfigMapRef cmRef =
        ConfigMapRef.of("runtime/daemonset-script-policy", nsRef, "flox-daemonset-script-policy");
    ApiObject configMap =
        new ApiObject(
            chart,
            "configmap-flox-daemonset-script-policy",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ConfigMap")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("flox-daemonset-script-policy")
                        .namespace("rke2lab-system")
                        .build())
                .build());

    ApiObject found = resolver.require(cmRef);
    assertSame(configMap, found);
    assertEquals("ConfigMap", found.getKind());
    assertEquals("flox-daemonset-script-policy", found.getName());
  }

  @Test
  void requireDistinguishesSameNameAcrossNamespaces() {
    App app = new App(AppProps.builder().outdir("/tmp/cdk8s-resolver-test").build());
    Chart chart = new Chart(app, "test");
    Cdk8sApiObjectResolver resolver = new Cdk8sApiObjectResolver(chart);

    NamespaceRef nsA = NamespaceRef.of("a/ns", "ns-a");
    NamespaceRef nsB = NamespaceRef.of("b/ns", "ns-b");

    ApiObject inA =
        new ApiObject(
            chart,
            "configmap-shared-a",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ConfigMap")
                .metadata(ApiObjectMetadata.builder().name("shared").namespace("ns-a").build())
                .build());
    ApiObject inB =
        new ApiObject(
            chart,
            "configmap-shared-b",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("ConfigMap")
                .metadata(ApiObjectMetadata.builder().name("shared").namespace("ns-b").build())
                .build());

    assertSame(inA, resolver.require(ConfigMapRef.of("a/shared", nsA, "shared")));
    assertSame(inB, resolver.require(ConfigMapRef.of("b/shared", nsB, "shared")));
  }

  @Test
  void requireUsesCache() {
    App app = new App(AppProps.builder().outdir("/tmp/cdk8s-resolver-test").build());
    Chart chart = new Chart(app, "test");
    Cdk8sApiObjectResolver resolver = new Cdk8sApiObjectResolver(chart);

    NamespaceRef ref = NamespaceRef.of("cluster/runtime-system-namespace", "rke2lab-system");
    new ApiObject(
        chart,
        "namespace-rke2lab-system",
        ApiObjectProps.builder()
            .apiVersion("v1")
            .kind("Namespace")
            .metadata(ApiObjectMetadata.builder().name("rke2lab-system").build())
            .build());

    ApiObject first = resolver.require(ref);
    ApiObject second = resolver.require(ref);
    assertSame(first, second);
  }

  @Test
  void requireThrowsIfNotFound() {
    App app = new App(AppProps.builder().outdir("/tmp/cdk8s-resolver-test").build());
    Chart chart = new Chart(app, "test");
    Cdk8sApiObjectResolver resolver = new Cdk8sApiObjectResolver(chart);

    NamespaceRef ref = NamespaceRef.of("cluster/runtime-system-namespace", "rke2lab-system");

    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> resolver.require(ref));

    assertTrue(ex.getMessage().contains("No ApiObject found"));
    assertTrue(ex.getMessage().contains("kind=Namespace"));
    assertTrue(ex.getMessage().contains("name=rke2lab-system"));
  }
}
