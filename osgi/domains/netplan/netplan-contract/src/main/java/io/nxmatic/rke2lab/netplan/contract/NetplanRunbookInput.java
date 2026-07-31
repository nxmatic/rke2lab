package io.nxmatic.rke2lab.netplan.contract;

import io.nxmatic.rke2lab.seed.broker.port.Amendment;
import io.nxmatic.rke2lab.seed.broker.port.SeedContract;
import java.util.Optional;

/**
 * The wire contract for the netplan {@code runbook} trigger — the activation payload a sower
 * supplies to play the netplan blueprint-export scion. The INPUT twin of a reaped wire-record: the
 * {@code shape} meta-coordinate projects THIS record's JSON Schema so a sower learns the shape from
 * the broker door rather than compiling the class.
 *
 * <p>It carries a SINGLE {@link Amendment}: {@link Amendment#SOIL} — {@link #materializationRoot}
 * is the plot the scion writes {@code blueprint.json} into, which only the host knows (it creates
 * the export directory). The host fills it by role — the SOIL amendment — never by field name.
 * {@link Optional#empty()} when unamended (a bare {@code shape} probe) → the scion writes into a
 * temp dir; absence is an empty {@link Optional}, never a blank string. The blueprint itself is
 * fully derived in-container ({@link ClusterNetworkBlueprint#deriveRecipeModel} is a pure
 * function), so this input carries no facet — where to write is the only host-held fact.
 */
@SeedContract("runbook")
public record NetplanRunbookInput(@Amendment(Amendment.SOIL) Optional<String> materializationRoot) {

  /**
   * The seed a scion holds before a sow arrives: an UNAMENDED soil ({@link Optional#empty()} → the
   * scion writes into a temp dir). Never a partial instance.
   */
  public static NetplanRunbookInput defaults() {
    return new NetplanRunbookInput(Optional.empty());
  }
}
