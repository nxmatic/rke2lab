package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.pulumi.edge.RunMode;
import io.nxmatic.rke2lab.seed.broker.port.Parcel;

/**
 * The commissioner's request — the run's facts, captured by {@code Main} INSIDE {@code Pulumi.run}
 * and carried to the scenario's GIVEN through the launcher session store. These are exactly the
 * facts only the Pulumi envelope can know: the {@link RunMode} (live vs preview, from {@code
 * isDryRun}), the {@link Parcel} (project/stack, from the Pulumi context), and the derived {@link
 * BootstrapConfig} (from the Pulumi {@code Config}), plus whether a clean worktree is required (the
 * entry-gate policy). The GIVEN bootstraps the open gardening from them; everything else it builds
 * itself. See docs/architecture/osgi/seed-bdd-module-spec.adoc (§ the amorce).
 *
 * <p>{@code txId} is the run's transaction id (a root-minted UUID) — carried on every {@code sow}
 * so a launched scion inherits it, for AUDIT correlation across the crossing (§
 * cellar-transactional). It is not the drain discriminant (the {@code RunRole} is); it is the
 * observability thread that ties a scion's work back to the run that sowed it.
 */
public record SeedRun(
    RunMode runMode,
    Parcel parcel,
    BootstrapConfig config,
    boolean cleanWorktreeRequired,
    String txId) {}
