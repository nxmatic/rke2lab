/**
 * Cluster API domain - CAPI operator, Incus provider (CAPN), and Stage A → Stage B handoff
 * resources. Demonstrates bootstrap identity access for infrastructure configuration.
 *
 * <h2>Domain Structure</h2>
 *
 * <pre>{@code
 * graph TB
 *     subgraph "Cluster API Domain"
 *         CDR[ClusterApiDomainRegistrar]
 *         BI[BootstrapIdentity]
 *     end
 *
 *     subgraph "Manifest Units"
 *         OPERATOR[ClusterApiOperatorManifestUnit<br/>CAPI + CAPN operators]
 *         IDENTITY[IncusIdentitySecretManifestUnit<br/>Incus credentials]
 *         IMAGE[ImageStateConfigMapManifestUnit<br/>Image fingerprint handoff]
 *     end
 *
 *     subgraph "Operator Environment"
 *         SECRETS[.secrets<br/>incus.capn.clientKey]
 *         INCUS[~/.config/incus/config.yml<br/>remotes: bioskop-nixos]
 *     end
 *
 *     CDR -->|creates| OPERATOR
 *     CDR -->|creates| IDENTITY
 *     CDR -->|creates| IMAGE
 *
 *     IDENTITY -->|reads| BI
 *     BI -->|incusRemoteName| IDENTITY
 *     IDENTITY -->|reads credentials| SECRETS
 *     IDENTITY -->|reads server cert| INCUS
 *
 *     IMAGE -->|reads| BI
 *     BI -->|clusterName| IMAGE
 *
 *     style OPERATOR fill:#e8f5e9,stroke:#1b5e20
 *     style IDENTITY fill:#e8f5e9,stroke:#1b5e20
 *     style IMAGE fill:#e8f5e9,stroke:#1b5e20
 *     style BI fill:#e1f5ff,stroke:#01579b
 *     style SECRETS fill:#fce4ec,stroke:#c2185b
 *     style INCUS fill:#fce4ec,stroke:#c2185b
 * }</pre>
 *
 * <h2>Bootstrap Identity Usage</h2>
 *
 * <p>This domain demonstrates accessing {@link
 * io.nxmatic.rk2lab.manifests.layers.common.profiles.BootstrapIdentity} for infrastructure
 * configuration:
 *
 * <pre>{@code
 * final BootstrapIdentity identity = bootstrapIdentity();
 * final String clusterName = identity.clusterName();           // "bioskop"
 * final String incusRemoteName = identity.incusRemoteName();   // "bioskop-nixos"
 *
 * // Read Incus config using the remote name (not cluster name!)
 * final String remoteAddress = readRemoteAddress(incusRemoteName, incusConfigDir);
 * }</pre>
 *
 * <p><b>Key insight</b>: Cluster name ≠ Incus remote name. The operator configures the mapping via
 * Pulumi:
 *
 * <pre>
 * rke2lab:cluster.name: "bioskop"
 * rke2lab:incus.defaultRemote: "bioskop-nixos"
 * </pre>
 *
 * <h2>Ephemeral Mode Handling</h2>
 *
 * <p>Manifest units that access operator environment files (credentials, Incus config) must handle
 * ephemeral synthesis mode (smoke tests, unit tests):
 *
 * <pre>{@code
 * if (BootstrapIdentity.UNKNOWN.equals(clusterName)) {
 *   return; // Skip synthesis in ephemeral mode
 * }
 * }</pre>
 *
 * <h2>Configuration</h2>
 *
 * <p>Pulumi stack configuration:
 *
 * <pre>
 * rke2lab:policy.link.clusterApi.enabled: "true"
 * rke2lab:cluster.name: "bioskop"
 * rke2lab:incus.defaultRemote: "bioskop-nixos"
 * </pre>
 *
 * <p>Operator environment files:
 *
 * <pre>
 * .secrets                           # incus.capn.clientKey (TLS private key)
 * ~/.config/incus/config.yml         # Incus remotes and server certs
 * </pre>
 *
 * <h2>Manifest Units</h2>
 *
 * <dl>
 *   <dt>{@link ClusterApiOperatorManifestUnit}
 *   <dd>Installs Cluster API core operator and Cluster API Provider Incus (CAPN). CAPN manages
 *       LXCCluster and LXCMachineTemplate resources for provisioning peer nodes via Incus.
 *   <dt>{@link IncusIdentitySecretManifestUnit}
 *   <dd>Creates the Incus identity secret in {@code capn-system} namespace with TLS client
 *       credentials and server address. CAPN uses this to authenticate with the Incus remote. Reads
 *       credentials from operator environment using {@code incusRemoteName} from BootstrapIdentity.
 *   <dt>{@link ImageStateConfigMapManifestUnit}
 *   <dd>Creates the image-state ConfigMap for Stage A → Stage B handoff. Contains image
 *       fingerprint, alias, and build checksum that seed-peers will use to synthesize
 *       LXCMachineTemplate CRs. Currently uses placeholder values; will be populated from
 *       imageProvider outputs in future iteration.
 * </dl>
 *
 * <h2>Related Documentation</h2>
 *
 * <ul>
 *   <li><a href="../../../../../../../../../../docs/manifest-conditional-inclusion.adoc">Manifest
 *       Conditional Inclusion</a> - Bootstrap identity access pattern
 *   <li><a href="../../../../../../../../../../docs/bootstrap-identity-provider.adoc">Bootstrap
 *       Identity Provider</a> - How identity flows through synthesis
 *   <li><a href="../../../../../../../../../../docs/gitops-cluster-api-transition-plan.md">GitOps +
 *       Cluster API Transition Plan</a> - Phase 1-3 implementation roadmap
 * </ul>
 *
 * @see ClusterApiDomainRegistrar
 * @see io.nxmatic.rk2lab.manifests.layers.common.profiles.BootstrapIdentity
 */
package io.nxmatic.rk2lab.manifests.layers.clusterapi;
