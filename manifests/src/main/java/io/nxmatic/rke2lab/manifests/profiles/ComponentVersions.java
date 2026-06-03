// @codebase
package io.nxmatic.rke2lab.manifests.profiles;

/**
 * Typed registry of bootstrap-layer component versions. Surfaced through {@link
 * io.nxmatic.rke2lab.manifests.ManifestSynthesisContext} and reachable by every manifest unit via
 * {@link io.nxmatic.rke2lab.manifests.AbstractManifestsUnit#componentVersions()}.
 *
 * <p>Replaces both the kpt-setter {@code ${...-version}} placeholders the deprecated branch carried
 * (and which leaked unsubstituted into the cdk8s-rendered output, see {@code
 * PorchResourcesLayer:200}'s {@code ${kube-vip-version}}) and the per-component hardcoded version
 * literals scattered across the codebase ({@code KubeVipComponent.java:227}, {@code
 * TektonPipelinesComponent.java:21}, {@code OpenebsZfsComponent.java:109}, ...).
 *
 * <p>Two-stage lookup:
 *
 * <ol>
 *   <li>{@link #defaults()} returns the all-components-pinned baseline — single grep target for
 *       "what version of operator X are we shipping in the bootstrap layer." Bumping a component is
 *       a one-line change here.
 *   <li>The seed-master entry point ({@code IncusResourceBootstrap.synthesizeAndExplodeManifests})
 *       layers Pulumi-config overrides ({@code rke2lab:components.<id>.version}) on top via {@link
 *       Builder#mergeFrom}. Defaults win if Pulumi config is silent.
 * </ol>
 *
 * <p>Bootstrap-only scope: only operators and runtime tools that land before Porch is up live here.
 * Workload versions for components Porch will own (cilium, envoy-gateway-instance, headscale,
 * tailscale per-cluster setters, kube-vip-tuning) move to the catalog repo's per-package {@code
 * Kptfile} once Porch reconciles them. Until then, the values for those components also live here
 * so the Pulumi-pre-rendered PackageVariants and Layer-rendered objects agree.
 */
public record ComponentVersions(
    String tektonOperator,
    String kubeVip,
    String openebsZfsChart,
    String kubernetesReplicator,
    String fluxOperator,
    String envoyGateway,
    String tailscale,
    String clusterApiOperator,
    String capiCore,
    String capiIncusProvider,
    String capiRke2Provider,
    String certManager) {

  public ComponentVersions {
    tektonOperator = blankToEmpty(tektonOperator);
    kubeVip = blankToEmpty(kubeVip);
    openebsZfsChart = blankToEmpty(openebsZfsChart);
    kubernetesReplicator = blankToEmpty(kubernetesReplicator);
    fluxOperator = blankToEmpty(fluxOperator);
    envoyGateway = blankToEmpty(envoyGateway);
    tailscale = blankToEmpty(tailscale);
    clusterApiOperator = blankToEmpty(clusterApiOperator);
    capiCore = blankToEmpty(capiCore);
    capiIncusProvider = blankToEmpty(capiIncusProvider);
    capiRke2Provider = blankToEmpty(capiRke2Provider);
    certManager = blankToEmpty(certManager);
  }

  /**
   * All-components-pinned baseline. Bumping a component's version is a one-line edit here and a
   * matching upstream artifact drop wherever the version is consumed (e.g. {@code
   * manifests/src/main/resources/upstream/cicd/tekton-operator/release-vX.Y.Z.yaml} for the Tekton
   * operator).
   *
   * <p>Sources of each pin (provenance):
   *
   * <ul>
   *   <li>{@code tektonOperator}: latest LTS at time of pin, see {@code TektonPipelinesComponent}
   *   <li>{@code kubeVip}: matched the deprecated/rke2lab-pre-pulumi-main pin
   *   <li>{@code openebsZfsChart}: chart version, kept in sync with the Helm release referenced by
   *       {@code OpenebsZfsComponent}
   *   <li>{@code kubernetesReplicator}: matches the {@code mittwald/kubernetes-replicator} chart
   *       version
   *   <li>{@code fluxOperator}: matches {@code controlplane/flux-operator} releases
   *   <li>{@code envoyGateway} / {@code tailscale}: matched the existing per-component literals
   *   <li>{@code clusterApiOperator}: CAPI operator managing core + providers declaratively
   *   <li>{@code capiCore}: {@code cluster-api/cluster-api} (CAPI) core provider version
   *   <li>{@code capiIncusProvider}: {@code lxc/cluster-api-provider-incus} (CAPN) version for
   *       provider CR
   *   <li>{@code capiRke2Provider}: {@code rancher/cluster-api-provider-rke2} (CAPRKE2) version for
   *       provider CR
   *   <li>{@code certManager}: {@code cert-manager/cert-manager} chart version — webhook-cert
   *       prerequisite for the CAPI operator and Tekton operator
   * </ul>
   */
  public static ComponentVersions defaults() {
    return builder()
        .tektonOperator("v0.79.1")
        .kubeVip("v0.8.7")
        .openebsZfsChart("2.8.0")
        .kubernetesReplicator("v2.12.2")
        .fluxOperator("v0.50.0")
        .envoyGateway("v1.4.2")
        .tailscale("1.82.0")
        .clusterApiOperator("v0.27.0")
        .capiCore("v1.9.4")
        .capiIncusProvider("v0.8.6")
        .capiRke2Provider("v0.24.4")
        .certManager("v1.20.2")
        .build();
  }

  /** Empty versions — used by tests and ephemeral synth runs that don't go through seed-master. */
  public static ComponentVersions empty() {
    return builder().build();
  }

  public static Builder builder() {
    return new Builder();
  }

  public Builder toBuilder() {
    return new Builder()
        .tektonOperator(tektonOperator)
        .kubeVip(kubeVip)
        .openebsZfsChart(openebsZfsChart)
        .kubernetesReplicator(kubernetesReplicator)
        .fluxOperator(fluxOperator)
        .envoyGateway(envoyGateway)
        .tailscale(tailscale)
        .clusterApiOperator(clusterApiOperator)
        .capiCore(capiCore)
        .capiIncusProvider(capiIncusProvider)
        .capiRke2Provider(capiRke2Provider)
        .certManager(certManager);
  }

  private static String blankToEmpty(final String value) {
    return value == null ? "" : value;
  }

  public static final class Builder {
    private String tektonOperator = "";
    private String kubeVip = "";
    private String openebsZfsChart = "";
    private String kubernetesReplicator = "";
    private String fluxOperator = "";
    private String envoyGateway = "";
    private String tailscale = "";
    private String clusterApiOperator = "";
    private String capiCore = "";
    private String capiIncusProvider = "";
    private String capiRke2Provider = "";
    private String certManager = "";

    private Builder() {}

    public Builder tektonOperator(final String v) {
      this.tektonOperator = v;
      return this;
    }

    public Builder kubeVip(final String v) {
      this.kubeVip = v;
      return this;
    }

    public Builder openebsZfsChart(final String v) {
      this.openebsZfsChart = v;
      return this;
    }

    public Builder kubernetesReplicator(final String v) {
      this.kubernetesReplicator = v;
      return this;
    }

    public Builder fluxOperator(final String v) {
      this.fluxOperator = v;
      return this;
    }

    public Builder envoyGateway(final String v) {
      this.envoyGateway = v;
      return this;
    }

    public Builder tailscale(final String v) {
      this.tailscale = v;
      return this;
    }

    public Builder clusterApiOperator(final String v) {
      this.clusterApiOperator = v;
      return this;
    }

    public Builder capiCore(final String v) {
      this.capiCore = v;
      return this;
    }

    public Builder capiIncusProvider(final String v) {
      this.capiIncusProvider = v;
      return this;
    }

    public Builder capiRke2Provider(final String v) {
      this.capiRke2Provider = v;
      return this;
    }

    public Builder certManager(final String v) {
      this.certManager = v;
      return this;
    }

    /**
     * Layer overrides on top of an existing record (typically {@link #defaults()}). Empty values in
     * {@code other} are skipped — they don't clobber a non-empty default. Used by seed-master to
     * blend Pulumi-config overrides onto the baseline.
     */
    public Builder mergeFrom(final ComponentVersions other) {
      if (!other.tektonOperator.isEmpty()) tektonOperator = other.tektonOperator;
      if (!other.kubeVip.isEmpty()) kubeVip = other.kubeVip;
      if (!other.openebsZfsChart.isEmpty()) openebsZfsChart = other.openebsZfsChart;
      if (!other.kubernetesReplicator.isEmpty()) kubernetesReplicator = other.kubernetesReplicator;
      if (!other.fluxOperator.isEmpty()) fluxOperator = other.fluxOperator;
      if (!other.envoyGateway.isEmpty()) envoyGateway = other.envoyGateway;
      if (!other.tailscale.isEmpty()) tailscale = other.tailscale;
      if (!other.clusterApiOperator.isEmpty()) clusterApiOperator = other.clusterApiOperator;
      if (!other.capiCore.isEmpty()) capiCore = other.capiCore;
      if (!other.capiIncusProvider.isEmpty()) capiIncusProvider = other.capiIncusProvider;
      if (!other.capiRke2Provider.isEmpty()) capiRke2Provider = other.capiRke2Provider;
      if (!other.certManager.isEmpty()) certManager = other.certManager;
      return this;
    }

    public ComponentVersions build() {
      return new ComponentVersions(
          tektonOperator,
          kubeVip,
          openebsZfsChart,
          kubernetesReplicator,
          fluxOperator,
          envoyGateway,
          tailscale,
          clusterApiOperator,
          capiCore,
          capiIncusProvider,
          capiRke2Provider,
          certManager);
    }
  }
}
