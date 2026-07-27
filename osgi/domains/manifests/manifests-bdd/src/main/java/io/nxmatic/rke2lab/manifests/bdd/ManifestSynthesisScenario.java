package io.nxmatic.rke2lab.manifests.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.ScenarioState.Resolution;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import io.nxmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.nxmatic.rke2lab.manifests.contract.ManifestDomainPolicy;
import io.nxmatic.rke2lab.manifests.contract.ManifestSynthesisRequest;
import io.nxmatic.rke2lab.manifests.contract.ManifestSynthesisResult;
import io.nxmatic.rke2lab.manifests.contract.ManifestSynthesisService;
import io.nxmatic.rke2lab.manifests.contract.ManifestsRunbookInput;
import io.nxmatic.rke2lab.manifests.contract.profiles.BootstrapIdentity;
import io.nxmatic.rke2lab.manifests.contract.profiles.FloxDebugPolicy;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.InputReceiver;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.OsgiService;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioInputSeed;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.SeedScenario;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The manifests synthesis scenario, a production jGiven scenario told in the MANIFESTS DOMAIN's own
 * vocabulary — the operator's activation facet ({@link ManifestsRunbookInput}: which layers
 * publish, which debug) translated into the synthesis input and materialised, no host/Pulumi type.
 * Played IN-CONTAINER by the engine so the runbook shows a real node of the OSGi world; it is the
 * fifth scion, on the trio pattern the four contact scions share, with the differences its nature
 * forces (see docs/architecture/osgi/manifests-bdd-spec.adoc § what makes it different): it
 * synthesises + materialises rather than probing a live system, it READS its trigger (the others
 * ignore theirs), and it consults no doctor (a synthesis failure is a build defect, not a symptom).
 *
 * <p>The WHEN stage is the transposition of {@code HostSlotManifest.Builder.policy()} — the
 * projection the incus-bootstrap demolition orphaned — now OSGi-side: from the facet it derives the
 * {@link ManifestDomainPolicy} (synth-time filter), owning the {@link ManifestDomainCatalog}. So
 * the control-plane policy is reactivated INSIDE synthesis: the {@link ManifestSynthesisRequest}
 * carries it, and — threaded into the env-config unit — the {@code PublishNodeEnvContributor}
 * synthesises the {@code RKE2LAB_MANIFESTS_PUBLISH_*} env section the master's install/ready
 * scripts read (a normal ConfigMap, no out-of-band overlay) — invisible at the master frontier.
 *
 * <p>Its collaborator is INJECTED from its OWN bundle's registry by the {@link OsgiService} bridge:
 * the {@link ManifestSynthesisService} (the SCR-published synthesis; the env-config synthesis and
 * its {@code PublishNodeEnvContributor} run inside it). MODE-BLIND — it injects no run gate:
 * manifests is a pure FS materialiser with no live touch (no {@code Cultivating}/{@code Surveying}
 * pair), so it runs identically in both modes; the materialisation target is carried by the SOIL
 * amendment alone (the real tree when the host amended a plot, a temp dir for a bare survey), and
 * rendering the run PENDING under a surveying gate is the frontier's business (the engine's survey
 * executor), not the scenario's. The activation facet is seeded by the front-door via the inbound
 * {@link #INPUT} channel and received here ({@link InputReceiver}) before the play; the outbound
 * {@code ScenarioOutcome} channel harvests the played runbook.
 */
@SeedScenario
public class ManifestSynthesisScenario
    extends ScenarioTestBase<
        ManifestSynthesisScenario.Given,
        ManifestSynthesisScenario.When,
        ManifestSynthesisScenario.Then>
    implements InputReceiver<ManifestsRunbookInput>, ScenarioPlayer.Playable {

  private static final ManifestDomainCatalog CATALOG =
      ManifestDomainCatalog.builder().addDefaultDomains().addDefaultStageALinkableDomains().build();

  /**
   * The inbound channel the runbook handler ({@code ManifestsRunbookHandler.seedFrom}) seeds the
   * {@link ManifestsRunbookInput} facet through and this scenario receives it from (via {@link
   * InputReceiver}). Single-sourced here — the receiver owns the key + type — and referenced by the
   * handler for the seeding end ({@code INPUT.into(facet)}). Registered as a {@link
   * RegisterExtension} so its {@code TestInstancePostProcessor} fires before the body reads {@link
   * #input}.
   */
  @RegisterExtension
  public static final ScenarioInputSeed<ManifestsRunbookInput> INPUT =
      new ScenarioInputSeed<>(ManifestsRunbookInput.class, "manifests-runbook-input");

  private final Scenario<Given, When, Then> scenario = createScenario();

  // The activation facet the front-door seeds before the body (InputReceiver) — the operator's
  // choice (which layers publish, which debug) the WHEN translates. @MonotonicNonNull: null until
  // receiveInput sets it (before the body), then read.
  @MonotonicNonNull private ManifestsRunbookInput input;

  @Override
  public Scenario<Given, When, Then> getScenario() {
    return scenario;
  }

  @Override
  public void receiveInput(ManifestsRunbookInput input) {
    this.input = input;
  }

  @Test
  void the_manifests_are_synthesized_from_the_activation_facet() {
    final ManifestsRunbookInput facet =
        Objects.requireNonNull(input, "the activation facet was not seeded before the body");
    given().the_activation_facet(facet);
    when().the_policy_is_derived_from_the_facet().and().the_manifests_are_synthesized();
    then()
        .every_enabled_domain_produced_its_units()
        .and()
        .the_manifests_file_is_written()
        .and()
        .the_publish_env_section_is_synthesized();
  }

  /** Given: the activation facet and the synthesis collaborators. */
  public static class Given extends Stage<Given> {

    @ProvidedScenarioState ManifestsRunbookInput facet;

    @Hidden
    public Given the_activation_facet(ManifestsRunbookInput facet) {
      this.facet = facet;
      return self();
    }
  }

  /**
   * When: the transposition of {@code HostSlotManifest.Builder.policy()}. Derives the {@link
   * ManifestDomainPolicy} + {@link FloxDebugPolicy} from the facet and synthesises. The one policy
   * drives BOTH the synth-time domain filter AND — threaded through the synthesis into the
   * env-config unit — the {@code RKE2LAB_MANIFESTS_PUBLISH_*} publish section (no out-of-band
   * overlay). Mode-blind: the materialisation target follows the SOIL amendment alone (a temp dir
   * here), never a run gate.
   */
  public static class When extends Stage<When> {

    @ExpectedScenarioState ManifestsRunbookInput facet;

    // Injected straight from the bundle registry by the @OsgiService bridge (the stage creator) —
    // not threaded from the scenario through the Given as a step param.
    @OsgiService private Optional<ManifestSynthesisService> synthesis = Optional.empty();

    @ProvidedScenarioState ManifestDomainPolicy domainPolicy;
    @ProvidedScenarioState ManifestSynthesisResult result;

    // Name-resolved so the Then picks it by field name (matches the Then's stagingRoot).
    @ProvidedScenarioState(resolution = Resolution.NAME)
    Path stagingRoot;

    // The run's one output tree — created once by outdir(), exposed as stagingRoot so the Then can
    // checksum it. The materialisation target follows the SOIL amendment alone (a temp dir for a
    // bare survey); the run is mode-blind. @MonotonicNonNull: set once by outdir(), then read.
    @MonotonicNonNull private Path outdir;

    public When the_policy_is_derived_from_the_facet() {
      final ManifestsRunbookInput.PublishFacet publish = facet.facets().publish();
      // The one policy the run carries: base infra (cluster/runtime/platform) always on; the rest
      // follow the facet. It drives BOTH the synth-time domain filter AND — threaded through the
      // synthesis into the env-config unit — the PublishNodeEnvContributor's RKE2LAB_MANIFESTS_
      // PUBLISH_* section. No out-of-band overlay: the publish vars are a synthesised env section.
      this.domainPolicy =
          ManifestDomainPolicy.builder()
              .domainCatalog(CATALOG)
              .stageADefaults()
              .cluster(true)
              .runtime(true)
              .platform(true)
              .gitops(publish.gitops())
              .networking(publish.networking())
              .storage(publish.storage())
              .mesh(publish.mesh())
              .highAvailability(publish.highAvailability())
              .cicd(publish.cicd())
              .clusterApi(publish.clusterApi())
              .build();
      return self();
    }

    public When the_manifests_are_synthesized() {
      final ManifestsRunbookInput.DebugFacet debug = facet.facets().debug();
      final FloxDebugPolicy floxDebug =
          new FloxDebugPolicy(
              debug.mesh().enabled(),
              debug.networking().enabled(),
              debug.nriPlugins().flox().enabled());
      final Path root = outdir();
      // manifests.yaml is the INTERMEDIATE aggregate, not part of the mounted/checksummed tree — it
      // sits a level ABOVE the synthesis root (sibling of rke2-manifests.d), so the staging replica
      // the scion checksums holds only the manifest units, never the merged file. Falls back into
      // the root when the SOIL is a bare temp dir with no usable parent.
      final Path parent = root.getParent();
      final Path manifestFile = (parent == null ? root : parent).resolve("manifests.yaml");
      final ManifestSynthesisRequest.Builder builder =
          ManifestSynthesisRequest.builder(root, manifestFile)
              .manifestDomainPolicy(java.util.Optional.of(domainPolicy))
              .floxDebugPolicy(floxDebug);
      // The cross-frontier identity view: reaped ONCE at this scion via the WORKTREE amendment,
      // then
      // handed to synthesis on the request — the synthesis root threads one NodeEnvContext derived
      // from it to every unit. Absent (a bare survey / no worktree amended) → the request keeps its
      // unknown identity and the synthesis renders a clearly-blank cluster.
      facet
          .worktree()
          .ifPresent(
              w ->
                  builder.bootstrapIdentity(
                      BootstrapIdentity.builder()
                          .clusterName(w.clusterName())
                          .nodeName(w.nodeName())
                          .build()));
      final ManifestSynthesisRequest request = builder.build();
      try {
        this.result = synthesis.orElseThrow().synthesize(request);
      } catch (IOException ex) {
        throw new UncheckedIOException("manifests synthesis failed", ex);
      }
      return self();
    }

    private Path outdir() {
      if (outdir == null) {
        // The SOIL amendment: when the host amended the plot to materialise into, synthesise there
        // (the real provisioning tree). An empty soil = a survey / bare probe — materialise into a
        // temp dir so the run stays inert against the host FS. Mode-blind: the SOIL amendment alone
        // carries the live target; whether this run is a survey is the frontier's business, not the
        // scenario's, so there is no mode-tinted temp prefix.
        outdir =
            facet
                .materializationRoot()
                .map(soil -> Path.of(soil).toAbsolutePath().normalize())
                .orElseGet(this::freshTempDir);
        // Expose the resolved replica root so the Then checksums it and publishes the
        // host-manifest.
        this.stagingRoot = outdir;
      }
      return outdir;
    }

    private Path freshTempDir() {
      try {
        return Files.createTempDirectory("rke2lab-manifests-").toAbsolutePath().normalize();
      } catch (IOException ex) {
        throw new UncheckedIOException("cannot create the synthesis outdir", ex);
      }
    }
  }

  /**
   * Then: the two ends landed. Every enabled domain produced units (the synth-time filter); the
   * materialised tree is complete (manifest file exists, hit count &gt; 0); and the synthesised
   * manifests carry the publish env section ({@code RKE2LAB_MANIFESTS_PUBLISH_*}) — what the
   * master's scripts read.
   */
  public static class Then extends Stage<Then> {

    @ExpectedScenarioState ManifestDomainPolicy domainPolicy;
    @ExpectedScenarioState ManifestSynthesisResult result;

    @ExpectedScenarioState(resolution = Resolution.NAME)
    Path stagingRoot;

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

    public Then the_publish_env_section_is_synthesized() {
      final String rendered;
      try {
        rendered = Files.readString(result.manifestFile());
      } catch (IOException ex) {
        throw new UncheckedIOException(
            "cannot read the synthesised manifests " + result.manifestFile(), ex);
      }
      // The PublishNodeEnvContributor emits an env-section-publish ConfigMap during synthesis, so
      // the consolidated manifest names one RKE2LAB_MANIFESTS_PUBLISH_<LAYER>_ENABLED per
      // publishable
      // layer — what the master's install/ready scripts read. No out-of-band overlay.
      for (String domainId : CATALOG.stageALinkableDomains()) {
        final String var =
            "RKE2LAB_MANIFESTS_PUBLISH_"
                + domainId.toUpperCase(Locale.ROOT).replace('-', '_')
                + "_ENABLED";
        if (!rendered.contains(var)) {
          throw new AssertionError("synthesised manifests miss the publish var " + var);
        }
      }
      return self();
    }
  }
}
