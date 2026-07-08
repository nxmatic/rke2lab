package io.nxmatic.rke2lab.cluster.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.Quoted;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import com.tngtech.jgiven.junit5.JGivenExtension;
import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.cluster.port.ClusterReadinessContact;
import io.nxmatic.rke2lab.cluster.port.ClusterReadinessPhase;
import io.nxmatic.rke2lab.cluster.port.ControllerRef;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.osgi.framework.FrameworkUtil;

/**
 * The cluster-readiness checkpoint, a production jGiven scenario told in the CLUSTER DOMAIN's own
 * vocabulary — {@link ClusterReadinessContact} probed across its {@link ClusterReadinessPhase}s
 * over a kubeconfig, no host/Pulumi type. Played IN-CONTAINER by the engine so the runbook shows a
 * real node of the OSGi world; it lives in {@code cluster-bdd} (only ports, no sealed internal),
 * not a {@code -test} fragment (it is live seeding logic).
 *
 * <p>It resolves its collaborator — the {@link ClusterReadinessContact} — from its OWN bundle's
 * service registry ({@link FrameworkUtil}); the scenario is identical live and in test, only who
 * published the contact differs (the live {@code KubectlClusterContact}, or a mock a test seeds
 * into the registry before playing). The phases form a strict chain (kubeconfig → API →
 * controllers): a not-ready phase throws, jGiven marks it FAILED and skips the downstream chained
 * steps, so the runbook shows exactly where readiness broke.
 */
@ExtendWith(JGivenExtension.class)
public class ClusterReadinessScenario
    extends ScenarioTestBase<
        ClusterReadinessScenario.Given,
        ClusterReadinessScenario.When,
        ClusterReadinessScenario.Then> {

  // Scaffolding for increment 1: the front-door harvests the played model off this holder. REPLACED
  // in increment 2 by inject-the-model (the driver seeds its own ReportModel via the session store,
  // jGiven writes into it), when the cross-world graft lifts the model into the host runbook. An
  // initialized holder (never null) so the null-hygiene gate stays green; the run fills it.
  private static final AtomicReference<ReportModel> LAST_RUNBOOK = new AtomicReference<>();

  static ReportModel lastRunbook() {
    return Objects.requireNonNull(
        LAST_RUNBOOK.get(), "the scenario has not played yet — no runbook to harvest");
  }

  private final Scenario<Given, When, Then> scenario = createScenario();

  @Override
  public Scenario<Given, When, Then> getScenario() {
    return scenario;
  }

  @Test
  void the_cluster_becomes_ready() {
    final ClusterReadinessContact contact = resolveContact();
    given().the_cluster("seed", kubeconfig()).and().probed_through(contact);
    when()
        .the_kubeconfig_is_published()
        .and()
        .the_api_is_ready()
        .and()
        .the_required_controllers_are_effective();
    then().the_cluster_is_ready();
    LAST_RUNBOOK.set(getScenario().getModel());
  }

  /**
   * The kubeconfig the checkpoint reads the cluster through. A published path in live; in the
   * offline scenario the mock contact ignores it, so a fixed marker suffices to satisfy the
   * kubeconfig-published phase.
   */
  private static Path kubeconfig() {
    return Path.of("/srv/host/kubeconfig");
  }

  /**
   * Resolve the cluster contact from THIS bundle's registry — the in-container lookup ({@code
   * FrameworkUtil.getBundle(this).getBundleContext()}). A test seeds a mock under the same
   * interface before playing; live, SCR has published {@code KubectlClusterContact}.
   */
  private ClusterReadinessContact resolveContact() {
    final var context =
        Objects.requireNonNull(
                FrameworkUtil.getBundle(getClass()),
                "cluster-bdd is not bundle-loaded — the scenario must play in-container")
            .getBundleContext();
    final var ref =
        Objects.requireNonNull(
            context.getServiceReference(ClusterReadinessContact.class),
            "no ClusterReadinessContact in the registry (live edge or test mock must publish one)");
    return context.getService(ref);
  }

  /** Given: the kubeconfig to read the cluster through, the controllers to wait on, the contact. */
  public static class Given extends Stage<Given> {

    @ProvidedScenarioState Path kubeconfig;
    @ProvidedScenarioState List<ControllerRef> controllers = List.of();
    @ProvidedScenarioState ClusterReadinessContact contact;

    public Given the_cluster(@Quoted String name, @Hidden Path kubeconfig) {
      this.kubeconfig = kubeconfig;
      return self();
    }

    @Hidden
    public Given probed_through(ClusterReadinessContact contact) {
      this.contact = contact;
      return self();
    }
  }

  /**
   * When: each readiness phase is its own step, chained in canonical order. A phase the contact
   * reports not-ready throws, so jGiven marks its step FAILED and skips the downstream chained
   * steps — fail-fast is the chain's own semantics, and the operator still sees every phase.
   */
  public static class When extends Stage<When> {

    @ExpectedScenarioState Path kubeconfig;
    @ExpectedScenarioState List<ControllerRef> controllers;
    @ExpectedScenarioState ClusterReadinessContact contact;

    public When the_kubeconfig_is_published() {
      if (kubeconfig == null) {
        throw new AssertionError(ClusterReadinessPhase.KUBECONFIG_PUBLISHED.label() + ": absent");
      }
      return self();
    }

    public When the_api_is_ready() {
      if (!contact.isApiReady(kubeconfig)) {
        throw new AssertionError(ClusterReadinessPhase.API_READY.label() + ": not ready");
      }
      return self();
    }

    public When the_required_controllers_are_effective() {
      if (!contact.areControllersEffective(kubeconfig, controllers)) {
        throw new AssertionError(
            ClusterReadinessPhase.CONTROLLERS_EFFECTIVE.label() + ": not effective");
      }
      return self();
    }
  }

  /**
   * Then: the cluster is ready — reached only once every phase passed (a failing phase throws in
   * the When), the readable closing line, not where evaluation happens.
   */
  public static class Then extends Stage<Then> {

    public Then the_cluster_is_ready() {
      return self();
    }
  }
}
