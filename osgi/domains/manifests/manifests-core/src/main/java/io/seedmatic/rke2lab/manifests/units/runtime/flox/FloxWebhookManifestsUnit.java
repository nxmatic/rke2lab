package io.seedmatic.rke2lab.manifests.units.runtime.flox;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.Cdk8sApiObjectResolver;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.ManifestLayer;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import io.seedmatic.rke2lab.manifests.units.cluster.ClusterRefs;
import io.seedmatic.rke2lab.manifests.units.cluster.ClusterRuntimeNamespaceManifestsUnit;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

/**
 * Serves the flox-controller pod-mutating webhook: the TLS serving cert (minted + renewed
 * IN-CLUSTER by cert-manager from a self-signed {@code Issuer}), the {@code Service} that fronts
 * the flox-controller DaemonSet pods (the mutation is stateless, so any pod serves), and the {@code
 * MutatingWebhookConfiguration} routing pod CREATE to it.
 *
 * <p>The cert is a cert-manager concern, not a grow one (Door 2 of
 * docs/architecture/cluster-api/pac-in-cluster-render-spec.adoc § secret-delivery): a self-signed
 * {@code Issuer} signs a leaf {@code Certificate} whose {@code secretName} is {@value
 * #TLS_SECRET_NAME} (the DaemonSet mounts it), and the {@code MutatingWebhookConfiguration} carries
 * NO inline caBundle — the {@code cert-manager.io/inject-ca-from} annotation makes cert-manager's
 * ca-injector fill it (and keep it in sync on renewal). So the webhook rides no reveal-gated
 * secret: it renders UNCONDITIONALLY (a bare survey still emits the CRs; cert-manager materialises
 * the Secret in-cluster), and a secret-blind in-cluster render never strips it — the defect that
 * left the DaemonSet without {@code --enable-webhook} and the flox scheduling gate inert.
 *
 * <p>Single-source concord: the Service name/namespace ({@value #SERVICE_NAME} in {@code
 * rke2lab-system}) MUST match the {@code Certificate} SANs ({@link #servingDnsNames}) — otherwise
 * kube-apiserver rejects the TLS handshake. cert-manager itself is on the FOUNDATION layer (before
 * this OPERATORS unit), so the {@code Issuer}/{@code Certificate} CRDs exist and the leaf issues
 * before the DaemonSet mounts the Secret.
 *
 * <p>{@code failurePolicy: Ignore} keeps pod creation cluster-wide unblocked if the webhook is
 * momentarily unavailable (e.g. during a rollout); the injector self-filters (no-op on pods without
 * a {@code flox.seedmatic.io/environment.*} annotation), so the broad pod rule is cheap.
 *
 * <p>Single-tier self-signed leaf (the pattern the CAPI operator already runs in-cluster). The
 * planned convergence is a two-tier CA rooted in our own cluster-pki server-CA — see § FOLLOW-UP in
 * the flox-gate secret-flow record; nothing here forecloses it.
 */
public final class FloxWebhookManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.RUNTIME + "/flox-webhook";

  /** Exploded package dir (relative to the runtime domain). */
  public static final String OUTPUT_DIR = "flox-webhook";

  /** Service name — MUST match the serving cert SANs ({@link #servingDnsNames}). */
  public static final String SERVICE_NAME = "flox-controller-webhook";

  /** The controller DaemonSet's pod label the Service selects on (FloxControllerManifestsUnit). */
  private static final String CONTROLLER_NAME = "flox-controller";

  public static final String TLS_SECRET_NAME = "flox-controller-webhook-tls";

  /** The self-signed cert-manager Issuer that signs the serving leaf. */
  private static final String ISSUER_NAME = "flox-controller-webhook-selfsigned";

  /** The leaf serving Certificate; its caBundle is ca-injected onto the webhook config. */
  private static final String CERTIFICATE_NAME = "flox-controller-webhook-serving";

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
    final String namespace = ClusterRefs.RUNTIME_SYSTEM_NAMESPACE.name();

    final ApiObject issuer = createSelfSignedIssuer(scope, context.resolver(), namespace);
    createServingCertificate(scope, issuer, namespace);
    createService(scope, context.resolver(), namespace);
    createWebhookConfiguration(scope, namespace);
  }

  private ApiObject createSelfSignedIssuer(
      final Construct scope, final Cdk8sApiObjectResolver resolver, final String namespace) {
    final ApiObject issuer =
        new ApiObject(
            scope,
            "issuer-flox-webhook-selfsigned",
            ApiObjectProps.builder()
                .apiVersion("cert-manager.io/v1")
                .kind("Issuer")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(ISSUER_NAME)
                        .namespace(namespace)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "cert-manager.io|Issuer|" + namespace + "|" + ISSUER_NAME))
                        .build())
                .build());
    issuer.addDependency(resolver.require(ClusterRefs.RUNTIME_SYSTEM_NAMESPACE));
    issuer.addJsonPatch(JsonPatch.add("/spec", Map.of("selfSigned", Map.of())));
    return issuer;
  }

  private void createServingCertificate(
      final Construct scope, final ApiObject issuer, final String namespace) {
    final ApiObject certificate =
        new ApiObject(
            scope,
            "certificate-flox-webhook-serving",
            ApiObjectProps.builder()
                .apiVersion("cert-manager.io/v1")
                .kind("Certificate")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(CERTIFICATE_NAME)
                        .namespace(namespace)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "cert-manager.io|Certificate|"
                                    + namespace
                                    + "|"
                                    + CERTIFICATE_NAME))
                        .build())
                .build());
    certificate.addDependency(issuer);
    certificate.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "secretName", TLS_SECRET_NAME,
                "dnsNames", servingDnsNames(namespace),
                "issuerRef", Map.of("kind", "Issuer", "name", ISSUER_NAME))));
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

  private void createWebhookConfiguration(final Construct scope, final String namespace) {
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
                        // cert-manager's ca-injector fills clientConfig.caBundle from this leaf's
                        // Secret and keeps it in sync on renewal — no inline caBundle, no reveal.
                        .annotations(
                            packageProfile.packageAnnotations(
                                "admissionregistration.k8s.io|MutatingWebhookConfiguration||"
                                    + WEBHOOK_CONFIG_NAME,
                                Map.of(
                                    "cert-manager.io/inject-ca-from",
                                    namespace + "/" + CERTIFICATE_NAME)))
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
                              "port", SERVICE_PORT))),
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

  // The serving SANs — the in-cluster Service DNS the kube-apiserver dials. MUST equal SERVICE_NAME
  // in the runtime namespace, both the bare .svc and the .svc.cluster.local form.
  private static Object[] servingDnsNames(final String namespace) {
    final String base = SERVICE_NAME + "." + namespace + ".svc";
    return new Object[] {base, base + ".cluster.local"};
  }
}
