// @codebase
package io.seedmatic.rke2lab.manifests.units.mesh;

import io.seedmatic.rke2lab.dataplan.contract.DataplanLayout;
import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.FloxAnnotation;
import io.seedmatic.rke2lab.manifests.contract.ManifestAnnotation;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.ManifestLayer;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

/**
 * Durable Tailscale funnel identity — the surgical fix for the Let's Encrypt rate-limit that
 * repeatedly wedged the render loop. The {@code pac-webhook} funnel's Let's Encrypt cert is
 * re-issued every cold-start because the proxy comes back as a NEW tailscale device (fresh node
 * key): the operator stores the proxy's tailscaled state (node key + cert) in a Secret named after
 * the pod ({@code TS_KUBE_SECRET=$(POD_NAME)}), which is etcd-ephemeral and lost on re-grow. Enough
 * re-grows in 168h hit the per-FQDN 5-cert limit → {@code getCertPEM: 429} → the funnel serves no
 * TLS → GitHub webhooks time out → zero PipelineRuns.
 *
 * <p>The fix persists ONLY the tailscale identity (not etcd — persisting etcd would make a
 * cold-start inconsistent), so a cold-start stays clean while the funnel returns as the SAME device
 * → Tailscale serves the existing cert, no re-issuance:
 *
 * <ol>
 *   <li>a {@link #proxyClass} pins {@code TS_KUBE_SECRET} to a STABLE name so the state Secret is
 *       addressable across grows (the Ingress opts in via {@code tailscale.com/proxy-class});
 *   <li>a static PV + {@code ZFSVolume} adopt the pre-declared dataplan dataset {@code
 *       tank/rke2lab/persist/funnel-cert} ({@code Retain}), bound by a stable PVC — the persist
 *       tier the dataplan created (a helper Job can mount it; the proxy itself cannot — ProxyClass
 *       exposes no volume);
 *   <li>a {@link #restoreJob} (before the operator provisions the proxy) writes the saved state
 *       back into the stable Secret, and a {@link #backupJob} (after the proxy stabilises) mirrors
 *       the current Secret back to the PVC. The node key never rotates, so no periodic poll — a
 *       one-shot backup per grow re-captures the current state (incl. any cert renewed in the last
 *       life).
 * </ol>
 *
 * <p>All state resources live in {@code mesh-system} (where the operator creates the proxy + its
 * state Secret); only the Ingress annotation is in {@code tekton-pipelines}. BEHAVIOURAL
 * ASSUMPTIONS proven at grow, not synth: the operator ADOPTS a pre-existing stable-named state
 * Secret; openebs ADOPTS the pre-declared dataset from a hand-authored ZFSVolume; the Flux layer
 * ordering lands the restore before the operator reconciles the Ingress into a proxy.
 */
public final class FunnelStatePersistenceManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.MESH + "/funnel-state";

  private static final String NAMESPACE = MeshRefs.MESH_SYSTEM_NAMESPACE.name();

  /** The stable proxy-state Secret name the ProxyClass pins TS_KUBE_SECRET to (vs the pod name). */
  public static final String STATE_SECRET = "ts-pac-webhook-state";

  /** The ProxyClass the pac-webhook Ingress opts into via tailscale.com/proxy-class. */
  public static final String PROXY_CLASS = "pac-webhook";

  /** The stable node name the persist dataset + PV are pinned to (openebs is node-local). */
  private static final String NODE_NAME = "bioskop-mgmt-master";

  /** The openebs deployment namespace ZFSVolume CRs live in. */
  private static final String OPENEBS_NAMESPACE = "openebs";

  private static final String STORAGE_CLASS = "openebs-zfs-persist";
  private static final String PV_NAME = "pac-webhook-funnel-cert";
  // A cert-state Secret mirror (tailscale node key + cert) is a few KB; 16Mi is generous headroom.
  // ZFS backs this as a DATASET with a refquota (thin) so the request is intent, not a reservation.
  private static final String CAPACITY = "16Mi";
  // Same size in bytes — openebs ZFSVolume.capacity wants a byte count. MUST equal CAPACITY.
  private static final String CAPACITY_BYTES = "16777216";

  /**
   * The mirror Jobs are flox-runtime workloads, not baked images: a minimal flox base image + the
   * {@code kube/base} FloxEnv (kubectl + yq-go) activated by the {@code
   * flox.seedmatic.io/environment.<container>} annotation the flox NRI keys on. No kubectl/jq baked
   * into an image — dogfooding the flox runtime, same as the render pipeline's nix injection.
   */
  private static final String MIRROR_ENV = "kube/base";

  /** The one container each mirror Job runs — the flox env annotation opts it in by name. */
  private static final String MIRROR_CONTAINER = "mirror";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("mesh", "funnel-state");

  private final DataplanLayout layout = DataplanLayout.canonical();

  public FunnelStatePersistenceManifestsUnit() {
    // The Tailscale operator (HelmChart, operators layer) must exist before the proxy is
    // provisioned
    // from the Ingress; this unit's ProxyClass + restore land alongside it.
    super(MANIFEST_UNIT_ID, List.of(TailscaleManifestsUnit.MANIFEST_UNIT_ID));
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    proxyClass(scope);
    zfsVolume(scope);
    persistentVolume(scope);
    persistentVolumeClaim(scope);
    serviceAccount(scope);
    role(scope);
    roleBinding(scope);
    restoreJob(scope);
    backupJob(scope);
  }

  /** ProxyClass pinning TS_KUBE_SECRET to a stable name — cluster-scoped. */
  private void proxyClass(final Construct scope) {
    final ApiObject proxyClass =
        new ApiObject(
            scope,
            "proxyclass-pac-webhook",
            ApiObjectProps.builder()
                .apiVersion("tailscale.com/v1alpha1")
                .kind("ProxyClass")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(PROXY_CLASS)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "",
                                Map.of(
                                    ManifestAnnotation.MANIFEST_LAYER.key(),
                                    ManifestLayer.OPERATORS.value())))
                        .build())
                .build());
    // The operator appends ProxyClass env AFTER its own, and a later value wins (verified in the
    // operator's sts.go), so this overrides the pod-named default → the state Secret is stable.
    proxyClass.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "statefulSet",
                Map.of(
                    "pod",
                    Map.of(
                        "tailscaleContainer",
                        Map.of(
                            "env",
                            new Object[] {
                              Map.of("name", "TS_KUBE_SECRET", "value", STATE_SECRET)
                            }))))));
  }

  /** The ZFSVolume CR adopting the pre-declared persist dataset (openebs namespace). */
  private void zfsVolume(final Construct scope) {
    final ApiObject zfsVolume =
        new ApiObject(
            scope,
            "zfsvolume-funnel-cert",
            ApiObjectProps.builder()
                .apiVersion("zfs.openebs.io/v1")
                .kind("ZFSVolume")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(PV_NAME)
                        .namespace(OPENEBS_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "",
                                Map.of(
                                    ManifestAnnotation.MANIFEST_LAYER.key(),
                                    ManifestLayer.OPERATORS.value())))
                        .build())
                .build());
    // ownerNodeID = the kubernetes node name (openebs is node-local); volumeType=DATASET for a zfs
    // filesystem; poolName = the dataplan persist pool (tank/rke2lab/persist). The dataset itself
    // is
    // pre-created by ndh from the dataplan — this CR makes openebs adopt it (proven at grow).
    zfsVolume.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "ownerNodeID", NODE_NAME,
                "poolName", layout.persistPool(),
                "capacity", "1073741824",
                "volumeType", "DATASET",
                "fsType", "zfs")));
  }

  /** The static PV bound to the ZFSVolume by volumeHandle — Retain, node-pinned. */
  private void persistentVolume(final Construct scope) {
    final ApiObject pv =
        new ApiObject(
            scope,
            "pv-funnel-cert",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("PersistentVolume")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(PV_NAME)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "",
                                Map.of(
                                    ManifestAnnotation.MANIFEST_LAYER.key(),
                                    ManifestLayer.OPERATORS.value())))
                        .build())
                .build());
    pv.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "capacity",
                Map.of("storage", CAPACITY),
                "accessModes",
                new Object[] {"ReadWriteOnce"},
                "persistentVolumeReclaimPolicy",
                "Retain",
                "storageClassName",
                STORAGE_CLASS,
                "volumeMode",
                "Filesystem",
                "claimRef",
                Map.of(
                    "namespace", NAMESPACE,
                    "name", PV_NAME),
                "csi",
                Map.of(
                    "driver",
                    "zfs.csi.openebs.io",
                    "fsType",
                    "zfs",
                    "volumeHandle",
                    PV_NAME,
                    "volumeAttributes",
                    Map.of("openebs.io/poolname", layout.persistPool())),
                "nodeAffinity",
                Map.of(
                    "required",
                    Map.of(
                        "nodeSelectorTerms",
                        new Object[] {
                          Map.of(
                              "matchExpressions",
                              new Object[] {
                                Map.of(
                                    "key", "openebs.io/nodename",
                                    "operator", "In",
                                    "values", new Object[] {NODE_NAME})
                              })
                        })))));
  }

  /** The stable PVC the helper Jobs mount, pre-bound to the static PV by volumeName. */
  private void persistentVolumeClaim(final Construct scope) {
    final ApiObject pvc =
        new ApiObject(
            scope,
            "pvc-funnel-cert",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("PersistentVolumeClaim")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(PV_NAME)
                        .namespace(NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "",
                                Map.of(
                                    ManifestAnnotation.MANIFEST_LAYER.key(),
                                    ManifestLayer.OPERATORS.value())))
                        .build())
                .build());
    pvc.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "accessModes",
                new Object[] {"ReadWriteOnce"},
                "storageClassName",
                STORAGE_CLASS,
                "volumeName",
                PV_NAME,
                "resources",
                Map.of("requests", Map.of("storage", CAPACITY)))));
  }

  private void serviceAccount(final Construct scope) {
    new ApiObject(
        scope,
        "sa-funnel-state",
        ApiObjectProps.builder()
            .apiVersion("v1")
            .kind("ServiceAccount")
            .metadata(
                ApiObjectMetadata.builder()
                    .name("funnel-state")
                    .namespace(NAMESPACE)
                    .annotations(
                        packageProfile.packageAnnotations(
                            "",
                            Map.of(
                                ManifestAnnotation.MANIFEST_LAYER.key(),
                                ManifestLayer.OPERATORS.value())))
                    .build())
            .build());
  }

  /** Namespaced Role: get/create/update the single state Secret the Jobs mirror. */
  private void role(final Construct scope) {
    final ApiObject role =
        new ApiObject(
            scope,
            "role-funnel-state",
            ApiObjectProps.builder()
                .apiVersion("rbac.authorization.k8s.io/v1")
                .kind("Role")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("funnel-state")
                        .namespace(NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "",
                                Map.of(
                                    ManifestAnnotation.MANIFEST_LAYER.key(),
                                    ManifestLayer.OPERATORS.value())))
                        .build())
                .build());
    role.addJsonPatch(
        JsonPatch.add(
            "/rules",
            new Object[] {
              Map.of(
                  "apiGroups", new Object[] {""},
                  "resources", new Object[] {"secrets"},
                  "verbs", new Object[] {"get", "create", "update", "patch"})
            }));
  }

  private void roleBinding(final Construct scope) {
    final ApiObject binding =
        new ApiObject(
            scope,
            "rolebinding-funnel-state",
            ApiObjectProps.builder()
                .apiVersion("rbac.authorization.k8s.io/v1")
                .kind("RoleBinding")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("funnel-state")
                        .namespace(NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "",
                                Map.of(
                                    ManifestAnnotation.MANIFEST_LAYER.key(),
                                    ManifestLayer.OPERATORS.value())))
                        .build())
                .build());
    binding.addJsonPatch(
        JsonPatch.add(
            "/roleRef",
            Map.of(
                "apiGroup", "rbac.authorization.k8s.io",
                "kind", "Role",
                "name", "funnel-state")),
        JsonPatch.add(
            "/subjects",
            new Object[] {
              Map.of("kind", "ServiceAccount", "name", "funnel-state", "namespace", NAMESPACE)
            }));
  }

  /**
   * Restore Job (operators layer, before the Ingress → proxy in workloads): if the PVC holds a
   * saved state Secret, apply it into the stable-named Secret so the operator's proxy adopts the
   * prior identity + cert. Idempotent — a fresh persist volume (no backup yet) is a clean first
   * grow.
   */
  private void restoreJob(final Construct scope) {
    job(
        scope,
        "job-funnel-restore",
        "funnel-cert-restore",
        ManifestLayer.OPERATORS,
        """
        set -euo pipefail
        if [ -s /persist/state.yaml ]; then
          echo "restoring saved tailscale funnel state into %s"
          kubectl apply -n %s -f /persist/state.yaml
        else
          echo "no saved funnel state on the persist volume — clean first grow"
        fi
        """
            .formatted(STATE_SECRET, NAMESPACE));
  }

  /**
   * Backup Job (workloads layer, after the proxy stabilises): mirror the current state Secret to
   * the persist volume, stripped of server-set metadata so it re-applies cleanly. Runs once per
   * grow — the node key never rotates, so no periodic poll; this re-captures whatever is current
   * (incl. a cert renewed during the last cluster's life).
   */
  private void backupJob(final Construct scope) {
    job(
        scope,
        "job-funnel-backup",
        "funnel-cert-backup",
        ManifestLayer.WORKLOADS,
        """
        set -euo pipefail
        echo "waiting for the tailscale funnel state secret %s"
        for i in $(seq 1 60); do
          if kubectl get -n %s secret %s >/dev/null 2>&1; then break; fi
          sleep 5
        done
        kubectl get -n %s secret %s -o yaml | yq 'del(.metadata.managedFields) | del(.metadata.resourceVersion) | del(.metadata.uid) | del(.metadata.creationTimestamp) | del(.metadata.ownerReferences) | del(.status)' > /persist/state.yaml
        echo "backed up funnel state to the persist volume"
        """
            .formatted(STATE_SECRET, NAMESPACE, STATE_SECRET, NAMESPACE, STATE_SECRET));
  }

  /** A one-shot Job mounting the persist PVC, running the given kubectl script in the layer. */
  private void job(
      final Construct scope,
      final String id,
      final String name,
      final ManifestLayer layer,
      final String script) {
    final String floxImage = ManifestSynthesisContext.current().floxDebugPolicy().prodImage();
    final ApiObject jobObject =
        new ApiObject(
            scope,
            id,
            ApiObjectProps.builder()
                .apiVersion("batch/v1")
                .kind("Job")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(name)
                        .namespace(NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "", Map.of(ManifestAnnotation.MANIFEST_LAYER.key(), layer.value())))
                        .build())
                .build());
    jobObject.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "backoffLimit",
                3,
                "template",
                Map.of(
                    // The flox NRI plugin only puts the env on PATH for a container NAMED by a
                    // flox.seedmatic.io/environment.<c> annotation — opt the mirror container into
                    // kube/base (kubectl + yq-go).
                    "metadata",
                    Map.of(
                        "annotations",
                        Map.of(
                            FloxAnnotation.ENVIRONMENT.forContainer(MIRROR_CONTAINER), MIRROR_ENV)),
                    "spec",
                    Map.of(
                        "serviceAccountName",
                        "funnel-state",
                        "restartPolicy",
                        "OnFailure",
                        "containers",
                        new Object[] {
                          Map.of(
                              "name",
                              MIRROR_CONTAINER,
                              "image",
                              floxImage,
                              // Enter the activated kube/base env, then run the mirror script.
                              "command",
                              new Object[] {
                                "flox", "activate", "--dir", "/root", "--", "bash", "-c", script
                              },
                              "volumeMounts",
                              new Object[] {Map.of("name", "persist", "mountPath", "/persist")})
                        },
                        "volumes",
                        new Object[] {
                          Map.of(
                              "name",
                              "persist",
                              "persistentVolumeClaim",
                              Map.of("claimName", PV_NAME))
                        })))));
  }
}
