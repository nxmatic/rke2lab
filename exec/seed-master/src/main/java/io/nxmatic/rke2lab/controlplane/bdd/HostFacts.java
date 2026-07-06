package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.controlplane.bbox.BboxReconciliationOrchestrator;
import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
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
 * <p>Carries {@link LiveGate}, not {@code RunMode}: the domain consumes the abstract live/deferred
 * face, never the Pulumi-vocabulary state it projects from — the edge does the projection.
 */
public record HostFacts(
    BootstrapConfig config,
    ControlplanePolicy policy,
    BootstrapOptions options,
    LiveGate liveGate,
    BboxReconciliationOrchestrator bboxOrchestrator,
    ResourceManager resourceManager,
    OutputBuilder outputBuilder,
    Consumer<String> readinessLogger,
    OnFailure onFailure,
    ConsultationLog consultations) {}
