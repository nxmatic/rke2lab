package io.nxmatic.rke2lab.manifests.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import com.tngtech.jgiven.junit5.JGivenExtension;
import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.nxmatic.rke2lab.manifests.contract.ManifestDomainPolicy;
import io.nxmatic.rke2lab.manifests.contract.ManifestSynthesisRequest;
import io.nxmatic.rke2lab.manifests.contract.ManifestSynthesisResult;
import io.nxmatic.rke2lab.manifests.contract.ManifestSynthesisService;
import io.nxmatic.rke2lab.manifests.contract.ManifestsRunbookInput;
import io.nxmatic.rke2lab.manifests.contract.node.NodeEnvContext;
import io.nxmatic.rke2lab.manifests.contract.node.NodeEnvOverlayService;
import io.nxmatic.rke2lab.manifests.contract.profiles.FloxDebugPolicy;
import io.nxmatic.rke2lab.manifests.node.DefaultNodeEnvContext;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioRegistry;
import io.nxmatic.rke2lab.seed.broker.port.RunGate;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The manifests synthesis scenario, a production jGiven scenario told in the MANIFESTS DOMAIN's own
 * vocabulary — the operator's activation facet ({@link ManifestsRunbookInput}: which layers link,
 * which debug) translated into the synthesis input and materialised, no host/Pulumi type. Played
 * IN-CONTAINER by the engine so the runbook shows a real node of the OSGi world; it is the fifth
 * scion, on the trio pattern the four contact scions share, with the differences its nature forces
 * (see docs/architecture/osgi/manifests-bdd-spec.adoc § what makes it different): it synthesises +
 * materialises rather than probing a live system, it READS its trigger (the others ignore theirs),
 * and it consults no doctor (a synthesis failure is a build defect, not a symptom).
 *
 * <p>The WHEN stage is the transposition of {@code HostSlotManifest.Builder.policy()} — the
 * projection the incus-bootstrap demolition orphaned — now OSGi-side: from the facet it derives
 * BOTH the {@link ManifestDomainPolicy} (synth-time filter) AND the {@code RKE2LAB_POLICY_LINK_*}
 * seed variables (link-time overlay), owning the {@link ManifestDomainCatalog} + the {@code
 * RKE2LAB_POLICY_LINK_*} naming. So the control-plane policy is reactivated INSIDE synthesis: the
 * {@link ManifestSynthesisRequest} carries the derived policy as a field, and {@code
 * writeControlplaneOverlay} lands the {@code 99-…} overlay the master's install/ready scripts read
 * — invisible at the master frontier (the founding rule).
 *
 * <p>It resolves its collaborators from its OWN bundle's registry ({@link ScenarioRegistry}): the
 * {@link ManifestSynthesisService} + {@link NodeEnvOverlayService} (the SCR-published synthesis)
 * and the ambient {@link RunGate} (whose {@link RunGate#cultivating() cultivating} decides whether
 * materialisation targets the real tree or a survey temp dir). The activation facet is seeded by
 * the handler via {@link #seedInput} before the play (the input twin of bbox's {@code lastRunbook}
 * harvest).
 */
@ExtendWith(JGivenExtension.class)
public class ManifestSynthesisScenario
    extends ScenarioTestBase<
        ManifestSynthesisScenario.Given,
        ManifestSynthesisScenario.When,
        ManifestSynthesisScenario.Then> {

  private static final ManifestDomainCatalog CATALOG =
      ManifestDomainCatalog.builder().addDefaultDomains().addDefaultStageALinkableDomains().build();

  // The handler seeds the activation facet here before the launcher plays (same-loader static, the
  // input twin of bbox's LAST_RUNBOOK); the front-door harvests the played model afterwards.
  // Initialized (never null) so the null-hygiene gate stays green.
  private static final AtomicReference<ManifestsRunbookInput> INPUT =
      new AtomicReference<>(ManifestsRunbookInput.defaults());
  private static final AtomicReference<ReportModel> LAST_RUNBOOK = new AtomicReference<>();

  /** The handler sets the sown facet here before selecting this class into the launcher. */
  public static void seedInput(ManifestsRunbookInput input) {
    INPUT.set(input);
  }

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
  void the_manifests_are_synthesized_from_the_activation_facet() {
    final ManifestsRunbookInput input = INPUT.get();
    final ManifestSynthesisService synthesis = resolve(ManifestSynthesisService.class);
    final NodeEnvOverlayService overlay = resolve(NodeEnvOverlayService.class);
    final RunGate gate = resolveGate();

    given().the_activation_facet(input).and().the_synthesis_services(synthesis, overlay, gate);
    when()
        .the_policy_is_derived_from_the_facet()
        .and()
        .the_manifests_are_synthesized()
        .and()
        .the_manifests_links_env_is_written();
    then()
        .every_enabled_domain_produced_its_units()
        .and()
        .the_manifests_file_is_written()
        .and()
        .the_overlay_carries_the_link_time_policy();

    LAST_RUNBOOK.set(getScenario().getModel());
  }

  private <T> T resolve(Class<T> type) {
    return ScenarioRegistry.of(this)
        .require(
            type,
            "no " + type.getSimpleName() + " in the registry (manifests-core must publish it)");
  }

  private RunGate resolveGate() {
    return ScenarioRegistry.of(this)
        .require(
            RunGate.class,
            "no RunGate in the registry (the host publishes it at boot, a test registers a mock)");
  }

  /** Given: the activation facet and the synthesis collaborators. */
  public static class Given extends Stage<Given> {

    @ProvidedScenarioState ManifestsRunbookInput facet;
    @ProvidedScenarioState ManifestSynthesisService synthesis;
    @ProvidedScenarioState NodeEnvOverlayService overlay;
    @ProvidedScenarioState RunGate gate;

    @Hidden
    public Given the_activation_facet(ManifestsRunbookInput facet) {
      this.facet = facet;
      return self();
    }

    @Hidden
    public Given the_synthesis_services(
        ManifestSynthesisService synthesis, NodeEnvOverlayService overlay, RunGate gate) {
      this.synthesis = synthesis;
      this.overlay = overlay;
      this.gate = gate;
      return self();
    }
  }

  /**
   * When: the transposition of {@code HostSlotManifest.Builder.policy()}. Derives the {@link
   * ManifestDomainPolicy} + {@link FloxDebugPolicy} + the {@code RKE2LAB_POLICY_LINK_*} seed vars
   * from the facet, synthesises, then writes the controlplane overlay — the two policy ends from
   * one payload. The gate is read so the scion plays honestly (survey targets a temp dir; the live
   * materialisation target is Family-2/checksum-glue territory, still a temp dir here).
   */
  public static class When extends Stage<When> {

    @ExpectedScenarioState ManifestsRunbookInput facet;
    @ExpectedScenarioState ManifestSynthesisService synthesis;
    @ExpectedScenarioState NodeEnvOverlayService overlay;
    @ExpectedScenarioState RunGate gate;

    @ProvidedScenarioState ManifestDomainPolicy domainPolicy;
    @ProvidedScenarioState Map<String, String> linkSeedVariables;
    @ProvidedScenarioState ManifestSynthesisResult result;
    @ProvidedScenarioState Path overlayFile;

    // The run's one output tree — created once, reused by both synthesis beats (units + overlay),
    // so
    // the Then can read the overlay the WHEN wrote. The gate is read so the scion plays honestly;
    // the live materialisation target is Family-2 territory (the checksum glue), a survey temp dir
    // here regardless of cultivating(). @MonotonicNonNull: set once by outdir(), then read.
    @MonotonicNonNull private Path outdir;

    public When the_policy_is_derived_from_the_facet() {
      final ManifestsRunbookInput.LinkFacet link = facet.link();
      // Synth-time: the 10-domain filter HostSlotManifest projected. cluster/runtime/platform are
      // always on (namespace, RKE2 config + Flox runtime, cert-manager/replicator); the rest follow
      // the facet.
      this.domainPolicy =
          ManifestDomainPolicy.builder()
              .domainCatalog(CATALOG)
              .stageADefaults()
              .cluster(true)
              .runtime(true)
              .platform(true)
              .gitops(link.gitops())
              .networking(link.networking())
              .storage(link.storage())
              .mesh(link.mesh())
              .highAvailability(link.highAvailability())
              .cicd(link.cicd())
              .clusterApi(link.clusterApi())
              .build();
      // Link-time: the 6 RKE2LAB_POLICY_LINK_* vars ManifestLinkPolicy.toEnvMap() exported (the
      // subset the master's install/ready scripts read), verbatim.
      final Map<String, String> vars = new LinkedHashMap<>();
      vars.put("RKE2LAB_POLICY_LINK_HIGH_AVAILABILITY_ENABLED", bool(link.highAvailability()));
      vars.put("RKE2LAB_POLICY_LINK_NETWORKING_ENABLED", bool(link.networking()));
      vars.put("RKE2LAB_POLICY_LINK_STORAGE_ENABLED", bool(link.storage()));
      vars.put("RKE2LAB_POLICY_LINK_MESH_ENABLED", bool(link.mesh()));
      vars.put("RKE2LAB_POLICY_LINK_CLUSTER_API_ENABLED", bool(link.clusterApi()));
      vars.put("RKE2LAB_POLICY_LINK_PLATFORM_ENABLED", bool(Boolean.TRUE));
      this.linkSeedVariables = Map.copyOf(vars);
      return self();
    }

    public When the_manifests_are_synthesized() {
      final ManifestsRunbookInput.DebugFacet debug = facet.debug();
      final FloxDebugPolicy floxDebug =
          new FloxDebugPolicy(
              debug.mesh().enabled(),
              debug.networking().enabled(),
              debug.nriPlugins().flox().enabled());
      final Path root = outdir();
      final ManifestSynthesisRequest request =
          ManifestSynthesisRequest.builder(root, root.resolve("manifests.yaml"))
              .manifestDomainPolicy(java.util.Optional.of(domainPolicy))
              .floxDebugPolicy(floxDebug)
              .build();
      try {
        this.result = synthesis.synthesize(request);
      } catch (IOException ex) {
        throw new UncheckedIOException("manifests synthesis failed", ex);
      }
      return self();
    }

    public When the_manifests_links_env_is_written() {
      final NodeEnvContext layerContext = new DefaultNodeEnvContext();
      final Path overlayRoot = outdir().resolve("rke2lab-environment.d");
      try {
        overlay.writeControlplaneOverlay(overlayRoot, layerContext, linkSeedVariables);
      } catch (IOException ex) {
        throw new UncheckedIOException("controlplane overlay write failed", ex);
      }
      // The 99-… overlay the master's install/ready scripts read — the Then asserts its data.
      this.overlayFile =
          overlayRoot.resolve("99-configmap-env-section-controlplane-layer-contributions.yml");
      return self();
    }

    private Path outdir() {
      if (outdir == null) {
        // The SOIL amendment: when the host amended the plot to materialise into, synthesise there
        // (the real provisioning tree). Blank soil = a survey / bare probe — materialise into a
        // temp dir so the run stays inert against the host FS. The gate only picks the temp prefix;
        // the SOIL amendment is what carries the live target.
        final String soil = facet.materializationRoot();
        if (!soil.isBlank()) {
          outdir = Path.of(soil).toAbsolutePath().normalize();
        } else {
          try {
            outdir =
                Files.createTempDirectory(
                        gate.cultivating()
                            ? "rke2lab-manifests-live-"
                            : "rke2lab-manifests-survey-")
                    .toAbsolutePath()
                    .normalize();
          } catch (IOException ex) {
            throw new UncheckedIOException("cannot create the synthesis outdir", ex);
          }
        }
      }
      return outdir;
    }

    private static String bool(boolean value) {
      return Boolean.toString(value);
    }
  }

  /**
   * Then: the two policy ends landed. Every enabled domain produced units (the synth-time filter);
   * the materialised tree is complete (manifest file + systemd units dir exist, hit count &gt; 0);
   * and the overlay carries the link-time {@code RKE2LAB_POLICY_LINK_*} — what the master's scripts
   * read.
   */
  public static class Then extends Stage<Then> {

    @ExpectedScenarioState ManifestDomainPolicy domainPolicy;
    @ExpectedScenarioState Map<String, String> linkSeedVariables;
    @ExpectedScenarioState ManifestSynthesisResult result;
    @ExpectedScenarioState Path envFile;

    public Then every_enabled_domain_produced_its_units() {
      final int enabled = domainPolicy.enabledDomainIds().size();
      if (result.domainCount() < enabled) {
        throw new AssertionError(
            "expected at least " + enabled + " synthesised domains, got " + result.domainCount());
      }
      return self();
    }

    public Then the_manifests_file_is_written() {
      if (!Files.exists(result.manifestFile())) {
        throw new AssertionError("manifest file was not written: " + result.manifestFile());
      }
      if (result.manifestUnitHitCount() <= 0) {
        throw new AssertionError("no manifest units were processed");
      }
      return self();
    }

    public Then the_overlay_carries_the_link_time_policy() {
      if (!Files.exists(envFile)) {
        throw new AssertionError("controlplane overlay was not written: " + envFile);
      }
      final String rendered;
      try {
        rendered = Files.readString(envFile);
      } catch (IOException ex) {
        throw new UncheckedIOException("cannot read the controlplane overlay " + envFile, ex);
      }
      // The 99-… ConfigMap's data carries each RKE2LAB_POLICY_LINK_* the master's install/ready
      // scripts read. Assert the rendered YAML names every link var — the link-time end landed.
      for (String key : linkSeedVariables.keySet()) {
        if (!rendered.contains(key)) {
          throw new AssertionError(
              "overlay does not carry the link-time policy var " + key + "\n" + rendered);
        }
      }
      return self();
    }
  }
}
