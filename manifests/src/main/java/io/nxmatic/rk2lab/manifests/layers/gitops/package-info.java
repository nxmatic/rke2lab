/**
 * GitOps domain - Flux CD installation and SOPS age secret management.
 *
 * <h2>Domain Structure</h2>
 *
 * <pre>{@code
 * graph TB
 *     subgraph "GitOps Domain"
 *         GDR[GitopsDomainRegistrar]
 *     end
 *
 *     subgraph "Manifest Units"
 *         FLUX[FluxInstanceManifestUnit<br/>Flux CD operators]
 *         SOPS[SopsAgeSecretManifestUnit<br/>SOPS age key]
 *         ROOT[FluxRootManifestUnit<br/>GitRepository + Kustomization]
 *     end
 *
 *     GDR -->|includes| FLUX
 *     GDR -->|includes| SOPS
 *     GDR -->|includes| ROOT
 *
 *     style FLUX fill:#e8f5e9,stroke:#1b5e20
 *     style SOPS fill:#e8f5e9,stroke:#1b5e20
 *     style ROOT fill:#e8f5e9,stroke:#1b5e20
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
