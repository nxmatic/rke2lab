// @codebase
package io.nxmatic.rke2lab.systemd.contract;

import io.nxmatic.rke2lab.seed.broker.port.Amendment;
import io.nxmatic.rke2lab.seed.broker.port.SeedContract;
import java.util.Optional;

/**
 * The wire contract for the systemd {@code runbook} trigger — the input twin of the incus/cluster
 * runbook inputs. It carries ONE amendment: the {@link Amendment#FACET} the host contributes
 * AMBIENT — the stable cluster/node {@link Identity}. The systemd scenario DERIVES the network
 * blueprint from that identity (OSGi-side, a pure {@code ClusterNetworkBlueprint} function) to
 * compose the probe endpoint — the node's mDNS FQDN paired with the systemd dbus port — so the host
 * names no systemd endpoint, only the neutral identity (the FACET discipline: the flat host holds
 * no domain type).
 *
 * <p>The amendment is {@link Optional}: EMPTY is the honest model of "unamended" (an offline play
 * or a survey), and the scenario then falls to a fixed marker the mock probe ignores — never a
 * record carried with blank sentinel fields.
 */
@SeedContract("runbook")
public record SystemdRunbookInput(@Amendment(Amendment.FACET) Optional<Identity> identity) {

  /** The default trigger — UNAMENDED (an offline play; the scenario uses the marker endpoint). */
  public static SystemdRunbookInput defaults() {
    return new SystemdRunbookInput(Optional.empty());
  }

  /**
   * The stable provisioning identity the host holds (its {@code BootstrapConfig}) — the cluster and
   * node names the scenario feeds {@code ClusterNetworkBlueprint.builder()} to derive the node's
   * mDNS FQDN and the {@code <cluster>-nixos} builder host. A sub-record filled blind by role,
   * mirroring the incus FACET — the host names no netplan or systemd type.
   */
  public record Identity(String clusterName, String nodeName) {}
}
