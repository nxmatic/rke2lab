package io.nxmatic.rke2lab.incus.contract;

import io.nxmatic.rke2lab.seed.broker.port.Amendment;
import io.nxmatic.rke2lab.seed.broker.port.SeedContract;

/**
 * The wire contract for the incus {@code runbook} trigger — the activation payload a sower supplies
 * to play the incus scion. Like {@code ManifestsRunbookInput} it is the INPUT twin of a reaped
 * wire-record; the {@code shape} meta-coordinate projects its JSON Schema so a sower learns the
 * shape from the broker door rather than compiling the class.
 *
 * <p>It carries one {@link Amendment}: {@link #materializationRoot} is the {@link Amendment#SOIL} —
 * the plot the instance's assets materialise into, which only the host knows (it holds {@code
 * BootstrapPaths}). The host fills it by role from its provisioning state (the staging-view {@code
 * assetsRoot}); the incus scion does NOT consume it directly to build the image — it FORWARDS it to
 * the manifests scion it consults, as the manifests SOIL, so the tree the instance mounts is
 * materialised under the same plot (see docs/architecture/osgi/bdd.adoc — the incus scion sows the
 * synthesis and uses the fresh graft). Blank when unamended (a bare {@code shape} probe, or an
 * offline scenario): the consult then falls back to a temp dir the way the manifests scion does.
 */
@SeedContract("runbook")
public record IncusRunbookInput(@Amendment(Amendment.SOIL) String materializationRoot) {

  /** The default trigger — an UNAMENDED soil (blank {@code materializationRoot}). */
  public static IncusRunbookInput defaults() {
    return new IncusRunbookInput("");
  }
}
