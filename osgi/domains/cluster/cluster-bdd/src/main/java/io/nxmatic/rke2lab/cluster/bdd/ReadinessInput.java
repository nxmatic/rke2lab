package io.nxmatic.rke2lab.cluster.bdd;

import io.nxmatic.rke2lab.seed.broker.port.Amendment;
import io.nxmatic.rke2lab.seed.broker.port.SeedContract;
import java.util.Optional;

/**
 * The wire contract for the cluster {@code runbook} trigger — the input twin of the systemd/incus
 * runbook inputs. It carries ONE amendment: the {@link Amendment#FACET} the host contributes — the
 * {@link Access} telling the readiness checkpoint WHERE the operator kubeconfig is published. The
 * host derives the path from its fat {@code BootstrapConfig} ({@code kubeconfigRef}); the cluster
 * reasoning sees only this neutral fact, never a host type.
 *
 * <p>It lives in {@code cluster-bdd} (with the scenario, reflector, and handler that are its only
 * users), NOT in {@code cluster-contract}: it bears {@code @SeedContract}/{@code @Amendment} so it
 * pulls the broker port, and cluster-contract must stay broker-free — else the broker package
 * bleeds into the {@code cluster-edge} import closure (the edge is a fabric8 contact that needs no
 * broker) and its in-container boot fails to resolve.
 *
 * <p>The amendment is {@link Optional}: EMPTY is the honest model of "unamended" (an offline play
 * or a survey), and the scenario then falls to a fixed marker the mock probe ignores — never a
 * record carried with a blank sentinel path.
 */
@SeedContract("runbook")
public record ReadinessInput(@Amendment(Amendment.FACET) Optional<Access> access) {

  /** The default trigger — UNAMENDED (an offline play; the scenario uses the marker path). */
  public static ReadinessInput defaults() {
    return new ReadinessInput(Optional.empty());
  }

  /**
   * WHERE the operator kubeconfig is published — the host-side path the readiness probe reads the
   * cluster through (fabric8 over a natively-trusted kubeconfig). Absolute+normalized by the
   * caller. A sub-record filled blind by role, mirroring the systemd/incus FACET — the host names
   * no cluster type.
   */
  public record Access(String kubeconfigPath) {}
}
