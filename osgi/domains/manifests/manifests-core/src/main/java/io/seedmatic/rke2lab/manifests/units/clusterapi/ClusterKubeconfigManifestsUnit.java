package io.seedmatic.rke2lab.manifests.units.clusterapi;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestAnnotations;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.profiles.BootstrapIdentity;
import io.seedmatic.rke2lab.manifests.contract.profiles.NetworkTopology;
import io.seedmatic.rke2lab.manifests.contract.profiles.OperatorPkiMaterial;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

/**
 * Renders the admin kubeconfig two ways from one {@link OperatorPkiMaterial}, discriminated by the
 * {@code config.kubernetes.io/local-config} annotation — no cdk8s extension, no host-side render
 * outside the synthesis pipeline:
 *
 * <ul>
 *   <li><b>The operator kubeconfig</b> — endpoint = the node's deterministic mDNS name ({@code
 *       <cluster>-<node>.local}), the endpoint seed-master reaches the master over. Carried in a
 *       {@code local-config} Secret so the exploder lands it as a hidden dotfile RKE2 never
 *       applies; the manifests scion reads it host-side and writes it to {@code kubeconfigRef} (the
 *       readiness probe's kubeconfig). The kpt-convention twin of the incus NoCloud cloud-config
 *       seed.
 *   <li><b>The CAPI kubeconfig Secret</b> — endpoint = the kube-vip VIP, the stable cluster
 *       endpoint new nodes join over. The canonical Cluster API {@code <cluster>-kubeconfig} Secret
 *       (data key {@code value}, type {@code cluster.x-k8s.io/secret}, {@code
 *       cluster.x-k8s.io/cluster-name} label) that CAPI reads in-cluster to seed the OTHER nodes
 *       (further control-plane + workers); seed-master itself only bootstraps the master control
 *       node. A visible manifest RKE2 applies.
 * </ul>
 *
 * <p>The unit only RENDERS. The manifests scion reveals the cluster-pki {@code AdminCredentials}
 * from the cellar in-container and translates it to {@link OperatorPkiMaterial} on the synthesis
 * request, so no {@code cluster-pki} type crosses into the manifests domain — the same channel
 * {@code IncusIdentityMaterial} / {@code SopsAgeMaterial} use. Absent material (a bare survey) or
 * an unknown cluster → the unit renders nothing.
 */
public final class ClusterKubeconfigManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.CLUSTER_API + "/kubeconfig";

  private static final String APISERVER_PORT = "6443";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile(ManifestDomainCatalog.CLUSTER_API, "kubeconfig");

  public ClusterKubeconfigManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final ManifestSynthesisContext synth = ManifestSynthesisContext.current();
    final String clusterName = synth.bootstrapIdentity().clusterName();
    final Optional<OperatorPkiMaterial> maybeMaterial = synth.operatorPki();

    if (BootstrapIdentity.UNKNOWN.equals(clusterName) || maybeMaterial.isEmpty()) {
      return;
    }
    final OperatorPkiMaterial material = maybeMaterial.orElseThrow();
    final String nodeName = synth.bootstrapIdentity().nodeName();
    final NetworkTopology topology = synth.networkTopology();

    // One namespace per managed cluster (CAPI convention) — the cluster's own name, so the Cluster
    // CR + its Machines + this kubeconfig Secret co-locate. The Namespace is cluster-scoped, so the
    // exploder orders it 01- ahead of the 02- Secret and RKE2 applies it first.
    final String clusterNamespace = clusterName;
    createClusterNamespace(scope, clusterNamespace);

    renderOperatorKubeconfig(scope, material, clusterName, clusterNamespace, nodeName);
    renderCapiKubeconfigSecret(
        scope, material, clusterName, clusterNamespace, topology.vipHostInetAddr());
  }

  private void createClusterNamespace(final Construct scope, final String namespace) {
    new ApiObject(
        scope,
        "namespace-" + namespace,
        ApiObjectProps.builder()
            .apiVersion("v1")
            .kind("Namespace")
            .metadata(
                ApiObjectMetadata.builder()
                    .name(namespace)
                    .annotations(packageProfile.packageAnnotations("|Namespace||" + namespace))
                    .build())
            .build());
  }

  // The operator kubeconfig over the deterministic mDNS name, host-consumed: a local-config Secret
  // the exploder hides as a dotfile (never applied); the scion reads it host-side into
  // kubeconfigRef.
  private void renderOperatorKubeconfig(
      final Construct scope,
      final OperatorPkiMaterial material,
      final String clusterName,
      final String namespace,
      final String nodeName) {
    final String name = clusterName + "-operator-kubeconfig";
    final String server = "https://" + clusterName + "-" + nodeName + ".local:" + APISERVER_PORT;

    final ApiObject secret =
        new ApiObject(
            scope,
            "secret-operator-kubeconfig",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Secret")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(name)
                        .namespace(namespace)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|Secret|" + namespace + "|" + name,
                                Map.of(ManifestAnnotations.LOCAL_CONFIG, "true")))
                        .build())
                .build());

    secret.addJsonPatch(JsonPatch.add("/type", "Opaque"));
    secret.addJsonPatch(
        JsonPatch.add(
            "/data", Map.of("kubeconfig.yaml", base64(material.kubeconfig(clusterName, server)))));
  }

  // The canonical CAPI <cluster>-kubeconfig Secret over the VIP, applied in-cluster: CAPI reads it
  // to
  // seed the further nodes. Data key `value`, type + cluster-name label per the CAPI contract.
  private void renderCapiKubeconfigSecret(
      final Construct scope,
      final OperatorPkiMaterial material,
      final String clusterName,
      final String namespace,
      final String vipHostInetAddr) {
    final String name = clusterName + "-kubeconfig";
    final String server = "https://" + vipHostInetAddr + ":" + APISERVER_PORT;

    final ApiObject secret =
        new ApiObject(
            scope,
            "secret-capi-kubeconfig",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Secret")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(name)
                        .namespace(namespace)
                        .labels(Map.of("cluster.x-k8s.io/cluster-name", clusterName))
                        .annotations(
                            packageProfile.packageAnnotations("|Secret|" + namespace + "|" + name))
                        .build())
                .build());

    secret.addJsonPatch(JsonPatch.add("/type", "cluster.x-k8s.io/secret"));
    secret.addJsonPatch(
        JsonPatch.add("/data", Map.of("value", base64(material.kubeconfig(clusterName, server)))));
  }

  private static String base64(final String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }
}
