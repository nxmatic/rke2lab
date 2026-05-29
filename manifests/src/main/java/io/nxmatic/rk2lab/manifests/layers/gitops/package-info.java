/**
 * GitOps domain - Flux CD installation and SOPS age secret management, with optional Porch
 * (Kubernetes package orchestration) resources.
 *
 * <h2>Domain Structure</h2>
 *
 * <pre>{@code
 * graph TB
 *     subgraph "GitOps Domain"
 *         GDR[GitopsDomainRegistrar]
 *         POLICY[ManifestDomainPolicy]
 *     end
 *
 *     subgraph "Mandatory Units"
 *         FLUX[FluxInstanceManifestUnit<br/>Flux CD operators]
 *         SOPS[SopsAgeSecretManifestUnit<br/>SOPS age key]
 *         ROOT[FluxRootManifestUnit<br/>GitRepository + Kustomization]
 *     end
 *
 *     subgraph "Optional Units"
 *         PORCH[PorchResourcesManifestUnit<br/>enabled by policy.link.porch]
 *     end
 *
 *     GDR -->|queries| POLICY
 *     POLICY -->|porch enabled?| GDR
 *     GDR -->|always includes| FLUX
 *     GDR -->|always includes| SOPS
 *     GDR -->|always includes| ROOT
 *     GDR -.->|conditionally includes| PORCH
 *
 *     style FLUX fill:#e8f5e9,stroke:#1b5e20
 *     style SOPS fill:#e8f5e9,stroke:#1b5e20
 *     style ROOT fill:#e8f5e9,stroke:#1b5e20
 *     style PORCH fill:#fff9c4,stroke:#f57f17
 *     style POLICY fill:#e1f5ff,stroke:#01579b
 * }</pre>
 *
 * <h2>Dependencies</h2>
 *
 * <p>The gitops domain depends on:
 *
 * <ul>
 *   <li><b>replication</b> - Longhorn storage for GitRepository persistence
 * </ul>
 *
 * <h2>Configuration</h2>
 *
 * <p>Pulumi stack configuration:
 *
 * <pre>
 * rke2lab:policy.link.gitops.enabled: "true"   # Enable domain
 * rke2lab:policy.link.porch.enabled: "false"   # Exclude Porch resources
 * </pre>
 *
 * <h2>Manifest Units</h2>
 *
 * <dl>
 *   <dt>{@link FluxInstanceManifestUnit}
 *   <dd>Installs Flux CD source-controller, kustomize-controller, helm-controller, and
 *       notification-controller operators into the cluster.
 *   <dt>{@link SopsAgeSecretManifestUnit}
 *   <dd>Creates the SOPS age secret for decrypting encrypted values in GitOps manifests. Reads age
 *       key from operator's {@code ~/.ndh-ssh.d/keys.yaml} (nix-darwin-home subtree).
 *   <dt>{@link FluxRootManifestUnit}
 *   <dd>Creates the root GitRepository and Kustomization resources that Flux uses to sync the
 *       cluster state from the git repository.
 *   <dt>{@link PorchResourcesManifestUnit} (optional)
 *   <dd>Creates Porch PackageVariant resources for peer cluster bootstrapping. Only included when
 *       {@code policy.link.porch.enabled=true}. Disabled by default as Stage A (master-only
 *       bootstrap) doesn't use Porch-based package orchestration.
 * </dl>
 *
 * <h2>Related Documentation</h2>
 *
 * <ul>
 *   <li><a href="../../../../../../../../../../docs/manifest-conditional-inclusion.adoc">Manifest
 *       Conditional Inclusion</a> - Pattern for optional units within domains
 *   <li><a href="../../../../../../../../../../docs/bootstrap-identity-provider.adoc">Bootstrap
 *       Identity Provider</a> - How cluster/node identity flows through synthesis
 * </ul>
 *
 * @see GitopsDomainRegistrar
 * @see io.nxmatic.rk2lab.manifests.api.ManifestDomainPolicy
 */
package io.nxmatic.rk2lab.manifests.layers.gitops;
