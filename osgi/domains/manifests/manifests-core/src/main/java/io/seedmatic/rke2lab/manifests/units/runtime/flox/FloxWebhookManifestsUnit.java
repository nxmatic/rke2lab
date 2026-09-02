package io.seedmatic.rke2lab.manifests.units.runtime.flox;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.Cdk8sApiObjectResolver;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.ManifestLayer;
import io.seedmatic.rke2lab.manifests.contract.profiles.WebhookServingMaterial;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import io.seedmatic.rke2lab.manifests.units.cluster.ClusterRefs;
import io.seedmatic.rke2lab.manifests.units.cluster.ClusterRuntimeNamespaceManifestsUnit;
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
 * Serves the flox-controller pod-mutating webhook: the TLS serving cert (a {@code
 * kubernetes.io/tls} Secret minted by the cluster-pki server-ca), the {@code Service} that fronts
 * the flox-controller DaemonSet pods (the mutation is stateless, so any pod serves), and the {@code
 * MutatingWebhookConfiguration} routing pod CREATE to it.
 *
 * <p>Single-source concord: the Service name/namespace ({@value #SERVICE_NAME} in {@code
 * rke2lab-system}) MUST match the SAN DNS names baked into the serving cert by {@code
 * ClusterSeal.WEBHOOK_SERVING_DNS} — otherwise kube-apiserver rejects the TLS handshake.
 *
 * <p>Rendered only when the serving material is present (a real seal); a bare survey / preview has
 * no cert, so the whole webhook (and its enablement on the DaemonSet) is absent — see the matching
 * guard in {@link FloxControllerManifestsUnit}.
 *
 * <p>On the {@code operators} layer with the controller. {@code failurePolicy: Ignore} keeps pod
 * creation cluster-wide unblocked if the webhook is momentarily unavailable (e.g. during a
 * rollout); the injector self-filters (no-op on pods without a {@code
 * flox.seedmatic.io/environment.*} annotation), so the broad pod rule is cheap.
 */
public final class FloxWebhookManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.RUNTIME + "/flox-webhook";

  /** Exploded package dir (relative to the runtime domain). */
  public static final String OUTPUT_DIR = "flox-webhook";

  /** Service name — MUST match the serving cert SAN (ClusterSeal.WEBHOOK_SERVING_DNS). */
  public static final String SERVICE_NAME = "flox-controller-webhook";

  /** The controller DaemonSet's pod label the Service selects on (FloxControllerManifestsUnit). */
  private static final String CONTROLLER_NAME = "flox-controller";

  public static final String TLS_SECRET_NAME = "flox-controller-webhook-tls";

  private static final String WEBHOOK_CONFIG_NAME = "flox-controller";

  /** controller-runtime's default mutating path for a corev1.Pod defaulter (empty group). */
  private static final String WEBHOOK_PATH = "/mutate--v1-pod";

  /** The controller-runtime webhook server listens on 9443; the Service publishes it as 443. */
  private static final int SERVICE_PORT = 443;

  private static final int WEBHOOK_TARGET_PORT = 9443;

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile(
          ManifestDomainCatalog.RUNTIME, OUTPUT_DIR, false, ManifestLayer.OPERATORS);

  public FloxWebhookManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of(ClusterRuntimeNamespaceManifestsUnit.MANIFEST_UNIT_ID));
  }

  @Override
  public String outputDir() {
    return OUTPUT_DIR;
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final Optional<WebhookServingMaterial> maybeMaterial =
        ManifestSynthesisContext.current().webhookServing();
    if (maybeMaterial.isEmpty()) {
      return; // no serving cert (bare survey / preview) — webhook absent, matching the DaemonSet
    }
    final WebhookServingMaterial material = maybeMaterial.orElseThrow();
    final String namespace = ClusterRefs.RUNTIME_SYSTEM_NAMESPACE.name();

    createServingSecret(scope, context.resolver(), material, namespace);
    createService(scope, context.resolver(), namespace);
    createWebhookConfiguration(scope, material, namespace);
  }

  private void createServingSecret(
      final Construct scope,
      final Cdk8sApiObjectResolver resolver,
      final WebhookServingMaterial material,
      final String namespace) {
    final ApiObject secret =
        new ApiObject(
            scope,
            "secret-flox-webhook-tls",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Secret")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(TLS_SECRET_NAME)
                        .namespace(namespace)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|Secret|" + namespace + "|" + TLS_SECRET_NAME))
                        .build())
                .build());
    secret.addDependency(resolver.require(ClusterRefs.RUNTIME_SYSTEM_NAMESPACE));
    secret.addJsonPatch(JsonPatch.add("/type", "kubernetes.io/tls"));
    secret.addJsonPatch(
        JsonPatch.add(
            "/data",
            Map.of(
                "tls.crt", base64(material.certPem()),
                "tls.key", base64(material.keyPem()))));
  }

  private void createService(
      final Construct scope, final Cdk8sApiObjectResolver resolver, final String namespace) {
    final ApiObject service =
        new ApiObject(
            scope,
            "service-flox-webhook",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Service")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(SERVICE_NAME)
                        .namespace(namespace)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|Service|" + namespace + "|" + SERVICE_NAME))
                        .build())
                .build());
    service.addDependency(resolver.require(ClusterRefs.RUNTIME_SYSTEM_NAMESPACE));
    service.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "selector",
                Map.of("app.kubernetes.io/name", CONTROLLER_NAME),
                "ports",
                new Object[] {
                  Map.of(
                      "port", SERVICE_PORT,
                      "targetPort", WEBHOOK_TARGET_PORT,
                      "protocol", "TCP")
                })));
  }

  private void createWebhookConfiguration(
      final Construct scope, final WebhookServingMaterial material, final String namespace) {
    final ApiObject config =
        new ApiObject(
            scope,
            "mutatingwebhookconfiguration-flox-controller",
            ApiObjectProps.builder()
                .apiVersion("admissionregistration.k8s.io/v1")
                .kind("MutatingWebhookConfiguration")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(WEBHOOK_CONFIG_NAME)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "admissionregistration.k8s.io|MutatingWebhookConfiguration||"
                                    + WEBHOOK_CONFIG_NAME))
                        .build())
                .build());
    config.addJsonPatch(
        JsonPatch.add(
            "/webhooks",
            new Object[] {
              Map.ofEntries(
                  Map.entry("name", "flox-inject.flox.seedmatic.io"),
                  Map.entry("admissionReviewVersions", new Object[] {"v1"}),
                  Map.entry("sideEffects", "None"),
                  // Ignore: never wedge cluster-wide pod creation on a transient webhook outage
                  // (the injector is a no-op on non-flox pods anyway). Tighten to Fail once proven.
                  Map.entry("failurePolicy", "Ignore"),
                  Map.entry("timeoutSeconds", 5),
                  Map.entry(
                      "clientConfig",
                      Map.of(
                          "service",
                          Map.of(
                              "name", SERVICE_NAME,
                              "namespace", namespace,
                              "path", WEBHOOK_PATH,
                              "port", SERVICE_PORT),
                          "caBundle",
                          base64(material.caBundlePem()))),
                  Map.entry(
                      "rules",
                      new Object[] {
                        Map.of(
                            "operations", new Object[] {"CREATE"},
                            "apiGroups", new Object[] {""},
                            "apiVersions", new Object[] {"v1"},
                            "resources", new Object[] {"pods"},
                            "scope", "Namespaced")
                      }),
                  // Bound the blast radius: never mutate control-plane/flux namespaces.
                  Map.entry(
                      "namespaceSelector",
                      Map.of(
                          "matchExpressions",
                          new Object[] {
                            Map.of(
                                "key",
                                "kubernetes.io/metadata.name",
                                "operator",
                                "NotIn",
                                "values",
                                new Object[] {
                                  "kube-system", "kube-public", "kube-node-lease", "flux-system"
                                })
                          })))
            }));
  }

  private static String base64(final String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }
}
