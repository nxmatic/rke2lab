package io.nxmatic.rke2lab.controlplane.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.NestedSteps;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.ScenarioStage;
import com.tngtech.jgiven.annotation.ScenarioState.Resolution;
import io.nxmatic.rke2lab.controlplane.incus.IncusResourceBootstrap.BootstrapResult;
import io.nxmatic.rke2lab.controlplane.readiness.ClusterBootstrapReadinessVerifier.VerificationResult;
import io.nxmatic.rke2lab.controlplane.resources.ResourceManager.ResourceCreationResult;
import io.nxmatic.rke2lab.controlplane.systemd.SeedSystemdAdapterRuntimeStatusSnapshot;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.OsgiConnection;
import io.nxmatic.rke2lab.systemd.port.SystemdRuntimeProbe;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Bootstrap resources, as a phase — the FAN-IN. It plays cluster-readiness NESTED (island 2, the
 * follow-the-chain dependency edge rendered as a sub-tree), then assembles the Pulumi/standalone
 * resources from the readiness result that phase produced. Its inputs are the upstream phases'
 * outputs, read as scenario-state: the incus {@code bootstrap} outcome, the systemd {@code
 * adapterLaunch} summary, and the {@code verification} the nested {@link ClusterReadinessStage}
 * provides. Resource creation itself is pure ({@code ResourceManager.createResources} receives the
 * already-played readiness result — the pipeline never plays a checkpoint).
 */
public class ResourcesStage extends Stage<ResourcesStage> {

  @ExpectedScenarioState HostFacts hostFacts;
  @ExpectedScenarioState OsgiConnection connection;

  /** The incus provisioning outcome (from IncusStage) — present when the mutation ran. */
  @ExpectedScenarioState @MonotonicNonNull Optional<BootstrapResult> bootstrap;

  /** The systemd-adapter launch summary (from SystemdAdapterStage) — the fan-in flux input. */
  @ExpectedScenarioState(resolution = Resolution.NAME)
  @MonotonicNonNull
  Map<String, Object> adapterLaunch;

  /** The readiness verdict the nested ClusterReadinessStage provides. */
  @ExpectedScenarioState @MonotonicNonNull VerificationResult verification;

  @ScenarioStage ClusterReadinessStage clusterReadiness;

  @ProvidedScenarioState @MonotonicNonNull ResourceCreationResult resources;

  /** Nested: play the cluster-readiness checkpoint as sub-steps before assembling the resources. */
  @NestedSteps
  @As("cluster readiness")
  ResourcesStage clusterReadiness() {
    clusterReadiness.the_cluster_is_verified_ready();
    return self();
  }

  @As("the bootstrap resources are created")
  public ResourcesStage the_bootstrap_resources_are_created() {
    clusterReadiness();

    // The upstream phases' outputs are set-once scenario-state (jGiven wires them before this
    // step);
    // guard each read, per the @MonotonicNonNull convention.
    final Optional<BootstrapResult> incus =
        Objects.requireNonNull(bootstrap, "bootstrap (incus phase not run)");
    final Map<String, Object> adapter =
        Objects.requireNonNull(adapterLaunch, "adapterLaunch (systemd phase not run)");
    final VerificationResult readiness =
        Objects.requireNonNull(verification, "verification (cluster-readiness phase not run)");

    final SeedSystemdAdapterRuntimeStatusSnapshot snapshot =
        new SeedSystemdAdapterRuntimeStatusSnapshot(
            connection.awaitService(SystemdRuntimeProbe.class, 5000));

    this.resources =
        hostFacts
            .resourceManager()
            .createResources(
                hostFacts.config(),
                hostFacts.readinessLogger(),
                Optional.of(hostFacts.consultations()),
                snapshot,
                incus.orElseThrow(
                    () -> new IllegalStateException("resources need the incus bootstrap result")),
                adapter,
                hostFacts.materialises(),
                hostFacts.liveGate(),
                readiness);
    return self();
  }
}
