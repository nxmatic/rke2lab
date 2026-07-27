package io.nxmatic.rke2lab.incus.ingress;

import io.nxmatic.rke2lab.seed.broker.port.SeedContract;
import java.util.List;

/**
 * The ONE immutable record the host GROW fetches to grow the instance — the incus scion PROJECTS
 * it, the pure-host GROW ACTUALISES it (the scion-projects / host-actualises rule, see
 * docs/architecture/osgi/host-cellar-realisation-spec.adoc § the-grow-anatomy). A single host
 * consumer, so a single record fetched once — assembled at the END by a sealing THEN ({@code
 * the_instance_grow_plan_is_published}), not accumulated by mutation. One store, one fetch, one
 * value-DAG. Self-contained: it carries everything the GROW poses — the host derives NO path, holds
 * no worktree root and no {@code BootstrapPaths}; the 13 disk {@link #mounts} are resolved
 * OSGi-side.
 *
 * <p>Structure: a sub-view per coherent GROUP of fields ({@link GrowNetworkView}, {@link
 * GrowImageView}), the resolved disk {@link GrowMountView mounts}, and a scalar at the ROOT for a
 * fact of the instance itself. {@link #cloudInitChecksum} is the nocloud checksum of the promoted
 * {@code host.live.d} — which equals its {@code syncedFrom} staging's, that {@code
 * HostStagingEntry} already carries per file (7a, no recompute) — projected flat here; the host
 * poses it on {@code user.rke2lab.provisioning.slice.*} to arm the nocloud→replace wire.
 *
 * <p>{@link SeedContract} binds it to the {@code instance-grow-plan} coordinate ({@link
 * IncusGrowCoordinate#INSTANCE_GROW_PLAN}) for the codec's decode guard. Unlike the OSGi-only
 * wire-records, this type IS held in both realms — the intra-domain dual-realm exception — so the
 * host {@code SeedCodec} decodes it against its own flat copy.
 */
@SeedContract("instance-grow-plan")
public record InstanceGrowPlan(
    GrowNetworkView network,
    GrowImageView image,
    String cloudInitChecksum,
    List<GrowMountView> mounts) {}
