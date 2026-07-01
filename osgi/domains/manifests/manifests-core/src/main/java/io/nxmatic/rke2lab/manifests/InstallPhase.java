// @codebase
package io.nxmatic.rke2lab.manifests;

import java.util.Optional;

/**
 * Where a {@link ManifestsUnit} attaches to RKE2's lifecycle.
 *
 * <p>RKE2 is not a single service we depend on — it is a multi-phase lifecycle. Manifests attach at
 * different phases, each with distinct ordering and an optional readiness gate. The systemd
 * synthesis derives ordering directives from this metadata; units never hand-write ordering.
 *
 * <p>See docs/rke2-install-phases.adoc for the full model and C4 diagrams.
 *
 * <p>The ready-gate is carried by the enum (convention), so the derivation stays generic: a gated
 * phase simply yields {@code readyGate().isPresent()} and the synthesizer adds {@code After=} that
 * service. Adding a gated phase is one enum entry, not a new branch.
 */
public enum InstallPhase {
  /** Before rke2-server starts; RKE2 consumes the manifest at boot (e.g. HelmChartConfig). */
  PRE_SERVER(Optional.empty()),

  /** API server up, RKE2 watches server/manifests/ (default, most domains). */
  POST_SERVER(Optional.empty()),

  /** After the Cilium CNI is Ready (Cilium CRDs exist) — e.g. cilium-advanced CRs. */
  POST_CNI_READY(Optional.of("rke2lab-cilium-ready.service")),

  /** After a specific operator's CRDs are established (e.g. vCluster/Flux for seed-vcluster). */
  POST_OPERATOR_READY(Optional.of("rke2lab-operator-ready.service"));

  private final Optional<String> readyGate;

  InstallPhase(Optional<String> readyGate) {
    this.readyGate = readyGate;
  }

  /** The systemd unit this phase must wait on, if any. Empty for PRE_SERVER / POST_SERVER. */
  public Optional<String> readyGate() {
    return readyGate;
  }

  /** Whether the installer for this phase runs before rke2-server (vs. after). */
  public boolean isPreServer() {
    return this == PRE_SERVER;
  }
}
