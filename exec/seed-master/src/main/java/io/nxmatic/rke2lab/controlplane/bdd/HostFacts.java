package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.config.port.BootstrapConfig;
import io.nxmatic.rke2lab.controlplane.bbox.BboxReconciliationOrchestrator;
import io.nxmatic.rke2lab.controlplane.pipeline.BootstrapOptions;
import io.nxmatic.rke2lab.controlplane.pipeline.OutputBuilder;
import io.nxmatic.rke2lab.controlplane.policy.ControlplanePolicy;
import io.nxmatic.rke2lab.controlplane.resources.ResourceManager;
import io.nxmatic.rke2lab.doctor.port.ConsultationLog;
import io.nxmatic.rke2lab.pipeline.OnFailure;
import io.nxmatic.rke2lab.pulumi.edge.LiveGate;
import java.util.function.Consumer;

/**
 * The host bag that crosses the launcher membrane inbound, seeded into the JUnit session store by
 * the driver and read onto the scenario by {@link HostSeeder}. The four OSGi services
 * (doctor/systemdProbe/clusterContact/readinessAuthority) are NOT here — the scenario resolves them
 * from its OSGi connection.
 *
 * <p>Carries {@code RunMode}'s TWO PROJECTIONS, never the enum itself: {@link LiveGate} (touch
 * reality? — the live/deferred face) and {@code materialises} (materialise Pulumi resources? — the
 * resource-path face the resources fan-in reads to pick Pulumi vs standalone). The domain consumes
 * the projections; the edge does the projection from {@code RunMode}, so no {@code com.pulumi}
 * vocabulary crosses.
 */
public record HostFacts(
    BootstrapConfig config,
    ControlplanePolicy policy,
    BootstrapOptions options,
    LiveGate liveGate,
    boolean materialises,
    BboxReconciliationOrchestrator bboxOrchestrator,
    ResourceManager resourceManager,
    OutputBuilder outputBuilder,
    Consumer<String> readinessLogger,
    OnFailure onFailure,
    ConsultationLog consultations) {}
