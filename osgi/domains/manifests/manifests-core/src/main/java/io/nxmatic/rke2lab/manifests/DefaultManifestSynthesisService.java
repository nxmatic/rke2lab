package io.nxmatic.rke2lab.manifests;

import com.fasterxml.jackson.core.type.TypeReference;
import io.nxmatic.rke2lab.manifests.cdk8s.Cdk8sApps;
import io.nxmatic.rke2lab.manifests.domain.CicdDomainRegistrar;
import io.nxmatic.rke2lab.manifests.domain.ClusterApiDomainRegistrar;
import io.nxmatic.rke2lab.manifests.domain.ClusterDomainRegistrar;
import io.nxmatic.rke2lab.manifests.domain.GitopsDomainRegistrar;
import io.nxmatic.rke2lab.manifests.domain.HighAvailabilityDomainRegistrar;
import io.nxmatic.rke2lab.manifests.domain.MeshDomainRegistrar;
import io.nxmatic.rke2lab.manifests.domain.NetworkingDomainRegistrar;
import io.nxmatic.rke2lab.manifests.domain.PlatformDomainRegistrar;
import io.nxmatic.rke2lab.manifests.domain.RuntimeDomainRegistrar;
import io.nxmatic.rke2lab.manifests.domain.StorageDomainRegistrar;
import io.nxmatic.rke2lab.manifests.node.NodeEnvContributorRegistry;
import io.nxmatic.rke2lab.manifests.port.ManifestDomainCatalog;
import io.nxmatic.rke2lab.manifests.port.ManifestDomainPolicy;
import io.nxmatic.rke2lab.manifests.port.ManifestSynthesisRequest;
import io.nxmatic.rke2lab.manifests.port.ManifestSynthesisResult;
import io.nxmatic.rke2lab.manifests.port.ManifestSynthesisService;
import io.nxmatic.rke2lab.manifests.port.SshToAgeConverter;
import io.nxmatic.rke2lab.manifests.port.profiles.SopsAgeMaterial;
import io.nxmatic.rke2lab.manifests.systemd.SystemdInfrastructureSynthesizer;
import io.nxmatic.rke2lab.pipeline.FluentTopicRunner;
import io.nxmatic.rke2lab.pipeline.OnFailure;
import io.nxmatic.rke2lab.pipeline.Topic;
import io.nxmatic.rke2lab.systemd.cdk8s.SystemdChart;
import io.nxmatic.rke2lab.systemd.cdk8s.SystemdDropIn;
import io.nxmatic.rke2lab.systemd.cdk8s.SystemdTarget;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.cdk8s.App;
import org.cdk8s.AppProps;
import org.cdk8s.Chart;
import org.jspecify.annotations.Nullable;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.resolver.Resolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Default SPI implementation for canonical manifest synthesis. */
@Component(service = ManifestSynthesisService.class)
public final class DefaultManifestSynthesisService implements ManifestSynthesisService {

  static final Logger LOG = LoggerFactory.getLogger(DefaultManifestSynthesisService.class);

  /**
   * The OSGi resolver SCR binds from the registry — felix.resolver's service, registered by its
   * bundle activator. Threaded into {@link ManifestsDomainRegistry#resolve(Resolver)}, the single
   * coherence gate, so this component never reaches into the resolver's impl package.
   */
  @Reference private Resolver resolver;

  /** SCR-injected registry of node-env contributors, threaded into each unit's context. */
  @Reference private NodeEnvContributorRegistry contributorRegistry;

  /**
   * The deterministic YAML service, threaded into each unit's context (units are not components).
   */
  @Reference private YamlMapper yaml;

  /**
   * The ssh-to-age edge, bound from the registry. Mandatory: without the edge the component never
   * activates, so {@code ManifestSynthesisService} never publishes — a missing edge fails the boot
   * fast rather than silently half-rendering the sops-age Secret. The pre-synthesis step calls it.
   */
  @Reference private SshToAgeConverter sshToAgeConverter;

  static final Set<String> SCRIPT_DATA_SUFFIXES =
      Set.of(".sh", ".bash", ".env", ".yaml", ".yml", ".conf", ".policy");

  static final TypeReference<Map<String, Object>> DOCUMENT_TYPE = new TypeReference<>() {};

  @Override
  public String providerId() {
    return "default-cdk8s-synthesizer";
  }

  @Override
  public ManifestSynthesisResult synthesize(ManifestSynthesisRequest request) throws IOException {
    LOG.info(
        "Starting manifests synthesis via provider '{}' (floxDebugPolicy={})",
        providerId(),
        request.floxDebugPolicy());

    // Pre-synthesis step: resolve the age key (read the SSH key from the key-store, convert it via
    // the ssh-to-age edge) BEFORE binding the context, so units only render — synthesis takes its
    // prerequisites, it does not fetch them.
    final SopsAgeMaterial sopsAgeMaterial =
        new SopsAgeMaterialResolver(sshToAgeConverter).resolve();

    final var contextScope = ManifestSynthesisContext.of(request, sopsAgeMaterial).bind();
    try (contextScope) {
      return synthesizeInContext(request);
    }
  }

  ManifestSynthesisResult synthesizeInContext(ManifestSynthesisRequest request) throws IOException {
    // Local pipeline for the manifest synthesis workflow (fluent grammar, see
    // docs/fluent-pipeline-grammar.adoc).
    // Structure: setup → registry → targets → units → finalization → synthesis
    final class SynthesisPipeline {
      final ManifestSynthesisRequest request;
      final FluentTopicRunner runner = new FluentTopicRunner("manifest-synthesis");

      SynthesisPipeline(ManifestSynthesisRequest request) {
        this.request = request;
      }

      AwaitingOnFailure onFailure(OnFailure handler) {
        return new AwaitingOnFailure(new State(request, handler));
      }

      /** Cdk8s app, charts and output paths — produced once by the setup stage. */
      record Scaffold(
          App app,
          Chart chart,
          SystemdChart systemdChart,
          Path synthOutdir,
          Path synthManifestFile,
          Path systemdOutdir) {}

      /** Configured domain registry and its unit hit count — produced by the registry stage. */
      record Registry(ManifestsDomainRegistry domainRegistry, int manifestUnitHitCount) {}

      /** Systemd target hierarchy and its context — produced by the systemd-targets stage. */
      record Targets(
          SystemdTarget rke2labTarget,
          SystemdTarget networkTarget,
          SystemdTarget toolsTarget,
          SystemdTarget bootstrapTarget,
          SystemdTarget manifestsTarget,
          SystemdTarget cniManifestsTarget,
          SystemdTarget operatorManifestsTarget,
          SystemdTarget secretsTarget,
          SystemdSynthesisContext systemdContext) {}

      /**
       * Working memory shared across pipeline stages. Each phase produces one immutable record; the
       * type-state chain guarantees a record is set before a later stage reads it, so the narrowing
       * accessors never fail. These three slots are the only mutable state.
       */
      final class State {
        final ManifestSynthesisRequest request;
        final OnFailure onFailure;

        @Nullable Scaffold scaffold;
        @Nullable Registry registry;
        @Nullable Targets targets;

        State(ManifestSynthesisRequest request, OnFailure onFailure) {
          this.request = request;
          this.onFailure = onFailure;
        }

        Scaffold scaffold() {
          return Objects.requireNonNull(scaffold, "cdk8s scaffold not yet produced");
        }

        Registry registry() {
          return Objects.requireNonNull(registry, "domain registry not yet produced");
        }

        Targets targets() {
          return Objects.requireNonNull(targets, "systemd targets not yet produced");
        }
      }

      final class AwaitingOnFailure {
        final State state;

        AwaitingOnFailure(State state) {
          this.state = state;
        }

        Cdk8sSetupDone during(String topic, Function<Cdk8sSetupStage, Cdk8sSetupStage> body) {
          final Cdk8sSetupStage stage =
              new Cdk8sSetupStage(state, scaffold -> state.scaffold = scaffold);
          runner.runDuring(topic, stage, body, state.onFailure);
          return new Cdk8sSetupDone(state);
        }
      }

      final class Cdk8sSetupStage implements Topic.Execution {
        final State state;
        final Sink sink;

        Cdk8sSetupStage(State state, Sink sink) {
          this.state = state;
          this.sink = sink;
        }

        /** The write-face of the cdk8s-setup topic. */
        interface Sink extends Topic.Sink {
          void scaffold(Scaffold scaffold);
        }

        @Override
        public String role() {
          return "cdk8s setup";
        }

        Cdk8sSetupStage createChartsAndPaths() {
          final Path synthOutdir = state.request.synthOutdir();
          final Path synthManifestFile = state.request.synthManifestFile();
          final Path systemdOutdir = synthOutdir.resolve("systemd");

          final App app =
              Cdk8sApps.create(AppProps.builder().outdir(synthOutdir.toString()).build());
          final Chart chart = new Chart(app, "manifests");
          final SystemdChart systemdChart = new SystemdChart(app, "systemd");

          sink.scaffold(
              new Scaffold(
                  app, chart, systemdChart, synthOutdir, synthManifestFile, systemdOutdir));

          return this;
        }
      }

      final class Cdk8sSetupDone {
        final State state;

        Cdk8sSetupDone(State state) {
          this.state = state;
        }

        AwaitingDomainRegistry then() {
          return new AwaitingDomainRegistry(state);
        }
      }

      final class AwaitingDomainRegistry {
        final State state;

        AwaitingDomainRegistry(State state) {
          this.state = state;
        }

        DomainRegistryDone during(
            String topic, Function<DomainRegistryStage, DomainRegistryStage> body) {
          final DomainRegistryStage stage =
              new DomainRegistryStage(state, registry -> state.registry = registry);
          runner.runDuring(topic, stage, body, state.onFailure);
          return new DomainRegistryDone(state);
        }
      }

      final class DomainRegistryStage implements Topic.Execution {
        final State state;
        final Sink sink;

        DomainRegistryStage(State state, Sink sink) {
          this.state = state;
          this.sink = sink;
        }

        /** The write-face of the domain-registry topic. */
        interface Sink extends Topic.Sink {
          void registry(Registry registry);
        }

        @Override
        public String role() {
          return "domain registry";
        }

        DomainRegistryStage buildAndApplyUnits() {
          final ManifestsDomainRegistry configuredDomainRegistry =
              buildDomainRegistry(state.request.manifestDomainPolicy());

          final ManifestsDomainRegistry domainRegistry =
              applyManifestDomainPolicy(state.request, configuredDomainRegistry);

          final Cdk8sApiObjectResolver resolver =
              new Cdk8sApiObjectResolver(state.scaffold().chart());
          final ManifestsUnitVisitor manifestUnitVisitor = new ApplyingManifestsUnitVisitor();

          LOG.info("Configured {} manifest domains", domainRegistry.domains().size());
          LOG.debug(
              "Manifest domains: {}",
              domainRegistry.domains().stream().map(ManifestsDomain::domainId).sorted().toList());

          final CoherentManifestsDomainRegistry coherent =
              domainRegistry.resolve(DefaultManifestSynthesisService.this.resolver);

          int manifestUnitHitCount = 0;
          for (ManifestsUnit manifestUnit : coherent.visitOrder()) {
            manifestUnitHitCount++;
            final String manifestUnitId = manifestUnit.manifestUnitId();
            LOG.debug("Applying manifest unit '{}'", manifestUnitId);
            final String domainId = coherent.requireDomainIdForManifestsUnit(manifestUnitId);
            manifestUnitVisitor.visit(
                manifestUnit,
                new ManifestsUnitContext(
                    state.scaffold().chart(),
                    domainId,
                    manifestUnitId,
                    resolver,
                    DefaultManifestSynthesisService.this.contributorRegistry,
                    DefaultManifestSynthesisService.this.yaml));
          }

          sink.registry(new Registry(domainRegistry, manifestUnitHitCount));

          return this;
        }
      }

      final class DomainRegistryDone {
        final State state;

        DomainRegistryDone(State state) {
          this.state = state;
        }

        AwaitingSystemdTargets then() {
          return new AwaitingSystemdTargets(state);
        }
      }

      final class AwaitingSystemdTargets {
        final State state;

        AwaitingSystemdTargets(State state) {
          this.state = state;
        }

        SystemdTargetsDone during(
            String topic, Function<SystemdTargetsStage, SystemdTargetsStage> body) {
          final SystemdTargetsStage stage =
              new SystemdTargetsStage(state, targets -> state.targets = targets);
          runner.runDuring(topic, stage, body, state.onFailure);
          return new SystemdTargetsDone(state);
        }
      }

      final class SystemdTargetsStage implements Topic.Execution {
        final State state;
        final Sink sink;

        SystemdTargetsStage(State state, Sink sink) {
          this.state = state;
          this.sink = sink;
        }

        /** The write-face of the systemd-targets topic. */
        interface Sink extends Topic.Sink {
          void targets(Targets targets);
        }

        @Override
        public String role() {
          return "systemd targets";
        }

        SystemdTargetsStage createTargetHierarchy() {
          final SystemdChart systemdChart = state.scaffold().systemdChart();
          final ManifestDomainCatalog domainCatalog =
              ManifestDomainCatalog.builder()
                  .addDefaultDomains()
                  .addDefaultStageALinkableDomains()
                  .build();

          LOG.debug("Creating systemd targets");
          final SystemdTarget rke2labTarget =
              new SystemdTarget(systemdChart, "rke2lab")
                  .description("RKE2 Lab Bootstrap Target")
                  .documentation("https://github.com/nxmatic/rke2lab")
                  .wantedBy("multi-user.target");

          final SystemdTarget networkTarget =
              new SystemdTarget(systemdChart, "rke2lab-network")
                  .description("RKE2 Lab Network Infrastructure Target")
                  .after("network-online.target")
                  .wants("network-online.target")
                  .partOf(rke2labTarget.getUnitFileName())
                  .wantedBy(rke2labTarget.getUnitFileName());

          final SystemdTarget toolsTarget =
              new SystemdTarget(systemdChart, "rke2lab-tools")
                  .description("RKE2 Lab Tools and Utilities Target")
                  .after(networkTarget.getUnitFileName())
                  .wants(networkTarget.getUnitFileName())
                  .partOf(rke2labTarget.getUnitFileName())
                  .wantedBy(rke2labTarget.getUnitFileName());

          final SystemdTarget bootstrapTarget =
              new SystemdTarget(systemdChart, "rke2lab-bootstrap")
                  .description("RKE2 Lab Early Bootstrap (pre-server)")
                  .after(networkTarget.getUnitFileName(), toolsTarget.getUnitFileName())
                  .requires(networkTarget.getUnitFileName(), toolsTarget.getUnitFileName())
                  .partOf(rke2labTarget.getUnitFileName())
                  .wantedBy(rke2labTarget.getUnitFileName())
                  .also(networkTarget.getUnitFileName(), toolsTarget.getUnitFileName());

          final SystemdTarget manifestsTarget =
              new SystemdTarget(systemdChart, "rke2lab-manifests")
                  .description("RKE2 Lab Manifest Installers (post-server)")
                  .after(bootstrapTarget.getUnitFileName(), "rke2-server.service")
                  .requires("rke2-server.service")
                  .partOf(rke2labTarget.getUnitFileName())
                  .wantedBy("rke2-server.service");

          final SystemdTarget cniManifestsTarget =
              new SystemdTarget(systemdChart, "rke2lab-cni-manifests")
                  .description("RKE2 Lab Manifest Installers (post-CNI-ready)")
                  .after(manifestsTarget.getUnitFileName(), "rke2-server.service")
                  .requires("rke2-server.service")
                  .partOf(rke2labTarget.getUnitFileName())
                  .wantedBy(rke2labTarget.getUnitFileName());

          final SystemdTarget operatorManifestsTarget =
              new SystemdTarget(systemdChart, "rke2lab-operator-manifests")
                  .description("RKE2 Lab Manifest Installers (post-operator-ready)")
                  .after(cniManifestsTarget.getUnitFileName(), "rke2-server.service")
                  .requires("rke2-server.service")
                  .partOf(rke2labTarget.getUnitFileName())
                  .wantedBy(rke2labTarget.getUnitFileName());

          final SystemdTarget secretsTarget =
              new SystemdTarget(systemdChart, "rke2lab-secrets")
                  .description("RKE2 Lab Secrets Installers (post-server)")
                  .after(
                      bootstrapTarget.getUnitFileName(),
                      manifestsTarget.getUnitFileName(),
                      "rke2-server.service")
                  .requires("rke2-server.service")
                  .partOf(rke2labTarget.getUnitFileName())
                  .wantedBy(rke2labTarget.getUnitFileName());

          final SystemdSynthesisContext systemdContext =
              SystemdSynthesisContext.builder()
                  .rke2labTarget(rke2labTarget)
                  .bootstrapTarget(bootstrapTarget)
                  .manifestsTarget(manifestsTarget)
                  .cniManifestsTarget(cniManifestsTarget)
                  .operatorManifestsTarget(operatorManifestsTarget)
                  .secretsTarget(secretsTarget)
                  .networkTarget(networkTarget)
                  .toolsTarget(toolsTarget)
                  .domainCatalog(domainCatalog)
                  .build();

          sink.targets(
              new Targets(
                  rke2labTarget,
                  networkTarget,
                  toolsTarget,
                  bootstrapTarget,
                  manifestsTarget,
                  cniManifestsTarget,
                  operatorManifestsTarget,
                  secretsTarget,
                  systemdContext));

          return this;
        }
      }

      final class SystemdTargetsDone {
        final State state;

        SystemdTargetsDone(State state) {
          this.state = state;
        }

        AwaitingSystemdUnits then() {
          return new AwaitingSystemdUnits(state);
        }
      }

      final class AwaitingSystemdUnits {
        final State state;

        AwaitingSystemdUnits(State state) {
          this.state = state;
        }

        SystemdUnitsDone during(String topic, Function<SystemdUnitsStage, SystemdUnitsStage> body) {
          final SystemdUnitsStage stage = new SystemdUnitsStage(state);
          runner.runDuring(topic, stage, body, state.onFailure);
          return new SystemdUnitsDone(state);
        }
      }

      final class SystemdUnitsStage implements Topic.Execution {
        final State state;

        SystemdUnitsStage(State state) {
          this.state = state;
        }

        @Override
        public String role() {
          return "systemd units";
        }

        SystemdUnitsStage synthesizeInfrastructureAndDomains() {
          final SystemdChart systemdChart = state.scaffold().systemdChart();
          final SystemdSynthesisContext systemdContext = state.targets().systemdContext();

          LOG.debug("Synthesizing bootstrap and infrastructure systemd units");
          new SystemdInfrastructureSynthesizer(systemdChart, systemdContext).synthesizeAll();

          for (ManifestsDomain domain : state.registry().domainRegistry().domains()) {
            LOG.debug("Synthesizing systemd units for domain '{}'", domain.domainId());
            domain.synthesizeSystemdUnits(systemdChart, systemdContext);
          }

          return this;
        }
      }

      final class SystemdUnitsDone {
        final State state;

        SystemdUnitsDone(State state) {
          this.state = state;
        }

        AwaitingTargetFinalization then() {
          return new AwaitingTargetFinalization(state);
        }
      }

      final class AwaitingTargetFinalization {
        final State state;

        AwaitingTargetFinalization(State state) {
          this.state = state;
        }

        TargetFinalizationDone during(
            String topic, Function<TargetFinalizationStage, TargetFinalizationStage> body) {
          final TargetFinalizationStage stage = new TargetFinalizationStage(state);
          runner.runDuring(topic, stage, body, state.onFailure);
          return new TargetFinalizationDone(state);
        }
      }

      final class TargetFinalizationStage implements Topic.Execution {
        final State state;

        TargetFinalizationStage(State state) {
          this.state = state;
        }

        @Override
        public String role() {
          return "target finalization";
        }

        TargetFinalizationStage finalizeAndCreateDropIn() {
          final SystemdChart systemdChart = state.scaffold().systemdChart();
          final Targets targets = state.targets();

          LOG.debug("Configuring main rke2lab.target dependencies");
          targets
              .rke2labTarget()
              .after(
                  targets.networkTarget().getUnitFileName(),
                  targets.toolsTarget().getUnitFileName(),
                  targets.bootstrapTarget().getUnitFileName(),
                  "rke2-server.service")
              .wants(
                  targets.networkTarget().getUnitFileName(),
                  targets.toolsTarget().getUnitFileName(),
                  targets.bootstrapTarget().getUnitFileName(),
                  targets.manifestsTarget().getUnitFileName(),
                  targets.cniManifestsTarget().getUnitFileName(),
                  targets.operatorManifestsTarget().getUnitFileName(),
                  targets.secretsTarget().getUnitFileName(),
                  "rke2-server.service");

          LOG.debug("Finalizing systemd target dependencies");
          systemdChart.finalizeTargetDependencies();

          LOG.debug("Creating rke2-server.service drop-in for lifecycle hooks");
          new SystemdDropIn(systemdChart, "rke2lab-server-hooks", "rke2-server.service")
              .execStartPre("/srv/host/systemd-scripts.d/rke2lab-server-pre-start.sh")
              .execStartPost("/srv/host/systemd-scripts.d/rke2lab-server-post-start.sh")
              .wants(targets.manifestsTarget().getUnitFileName());

          return this;
        }
      }

      final class TargetFinalizationDone {
        final State state;

        TargetFinalizationDone(State state) {
          this.state = state;
        }

        AwaitingSynthesis then() {
          return new AwaitingSynthesis(state);
        }
      }

      final class AwaitingSynthesis {
        final State state;

        AwaitingSynthesis(State state) {
          this.state = state;
        }

        SynthesisDone during(String topic, Function<SynthesisStage, SynthesisStage> body) {
          final SynthesisStage stage = new SynthesisStage(state);
          runner.runDuring(topic, stage, body, state.onFailure);
          return new SynthesisDone(state);
        }
      }

      final class SynthesisStage implements Topic.Execution {
        final State state;

        SynthesisStage(State state) {
          this.state = state;
        }

        @Override
        public String role() {
          return "synthesis";
        }

        SynthesisStage synthAndPostprocess() {
          final Scaffold scaffold = state.scaffold();
          try {
            LOG.info(
                "Calling app.synth() to synthesize K8s manifests to: {}", scaffold.synthOutdir());
            scaffold.app().synth();
            LOG.info(
                "app.synth() completed, now synthesizing systemd units to: {}",
                scaffold.systemdOutdir());
            scaffold.systemdChart().synthesize(scaffold.systemdOutdir());
            LOG.info("systemdChart.synthesize() completed");

            final Path synthesizedFile = getSynthesizedFile();

            enforceLiteralBlockStyleForConfigMapScripts(synthesizedFile);

            Files.createDirectories(scaffold.synthManifestFile().getParent());
            Files.move(
                synthesizedFile, scaffold.synthManifestFile(), StandardCopyOption.REPLACE_EXISTING);

            LOG.info(
                "Synthesized K8s manifests and systemd units from canonical manifest units (manifest unit hits={})",
                state.registry().manifestUnitHitCount());

            return this;
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        }

        private Path getSynthesizedFile() throws IOException {
          final Path synthOutdir = state.scaffold().synthOutdir();
          try (var files = Files.list(synthOutdir)) {
            return files
                .filter(
                    p -> {
                      final String name = p.getFileName().toString();
                      return name.endsWith("-manifests.k8s.yaml")
                          || name.equals("manifests.k8s.yaml");
                    })
                .filter(Files::exists)
                .findFirst()
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "Expected synthesized manifest file (manifests.k8s.yaml or"
                                + " *-manifests.k8s.yaml) is missing in: "
                                + synthOutdir));
          }
        }
      }

      final class SynthesisDone {
        final State state;

        SynthesisDone(State state) {
          this.state = state;
        }

        ManifestSynthesisResult complete() {
          final Scaffold scaffold = state.scaffold();
          final Registry registry = state.registry();
          return new ManifestSynthesisResult(
              scaffold.synthManifestFile(),
              scaffold.systemdOutdir(),
              registry.manifestUnitHitCount(),
              registry.domainRegistry().domains().size());
        }
      }
    }
    return new SynthesisPipeline(request)
        .onFailure((topic, cause) -> LOG.error("Synthesis failed in topic '{}'", topic, cause))
        .during("cdk8s setup", setup -> setup.createChartsAndPaths())
        .then()
        .during("domain registry", registry -> registry.buildAndApplyUnits())
        .then()
        .during("systemd targets", targets -> targets.createTargetHierarchy())
        .then()
        .during("systemd units", units -> units.synthesizeInfrastructureAndDomains())
        .then()
        .during("target finalization", finalization -> finalization.finalizeAndCreateDropIn())
        .then()
        .during("synthesis", synthesis -> synthesis.synthAndPostprocess())
        .complete();
  }

  ManifestsDomainRegistry buildDomainRegistry(Optional<ManifestDomainPolicy> policy) {
    final ManifestDomainPolicy effectivePolicy =
        policy.orElseGet(() -> ManifestDomainPolicy.builder().build());

    return new ManifestsDomainRegistryBuilder()
        .register(new ClusterDomainRegistrar(), effectivePolicy)
        .register(new StorageDomainRegistrar(), effectivePolicy)
        .register(new GitopsDomainRegistrar(), effectivePolicy)
        .register(new RuntimeDomainRegistrar(), effectivePolicy)
        .register(new NetworkingDomainRegistrar(), effectivePolicy)
        .register(new MeshDomainRegistrar(), effectivePolicy)
        .register(new HighAvailabilityDomainRegistrar(), effectivePolicy)
        .register(new CicdDomainRegistrar(), effectivePolicy)
        .register(new ClusterApiDomainRegistrar(), effectivePolicy)
        .register(new PlatformDomainRegistrar(), effectivePolicy)
        .build();
  }

  ManifestsDomainRegistry applyManifestDomainPolicy(
      ManifestSynthesisRequest request, ManifestsDomainRegistry configuredDomainRegistry) {
    if (request.manifestDomainPolicy().isEmpty()) {
      return configuredDomainRegistry;
    }

    final ManifestDomainPolicy manifestDomainPolicy = request.manifestDomainPolicy().orElseThrow();
    final Map<String, ManifestsDomain> configuredDomainsById =
        configuredDomainRegistry.domains().stream()
            .collect(
                Collectors.toMap(
                    ManifestsDomain::domainId,
                    domain -> domain,
                    (left, right) -> left,
                    LinkedHashMap::new));

    final List<String> unknownDomainIds =
        manifestDomainPolicy.domainIds().stream()
            .filter(domainId -> !configuredDomainsById.containsKey(domainId))
            .sorted()
            .toList();
    if (!unknownDomainIds.isEmpty()) {
      throw new IllegalArgumentException(
          "Manifest-domain policy references domains unsupported by provider '"
              + providerId()
              + "': "
              + unknownDomainIds);
    }

    final LinkedHashSet<String> requestedEnabledDomainIds =
        new LinkedHashSet<>(manifestDomainPolicy.enabledDomainIds());
    if (requestedEnabledDomainIds.isEmpty()) {
      throw new IllegalArgumentException(
          "Manifest-domain policy disables all domains for provider '" + providerId() + "'.");
    }

    final LinkedHashSet<String> effectiveDomainIds = new LinkedHashSet<>();
    for (String requestedDomainId : requestedEnabledDomainIds) {
      collectDomainDependencies(requestedDomainId, configuredDomainsById, effectiveDomainIds);
    }

    LOG.info(
        "Applying manifest-domain policy: requested domains={}, effective domains={}",
        requestedEnabledDomainIds,
        effectiveDomainIds);

    final List<ManifestsDomain> filteredDomains =
        configuredDomainRegistry.domains().stream()
            .filter(domain -> effectiveDomainIds.contains(domain.domainId()))
            .toList();
    return new ManifestsDomainRegistry(filteredDomains);
  }

  void collectDomainDependencies(
      String domainId,
      Map<String, ManifestsDomain> configuredDomainsById,
      Set<String> effectiveDomainIds) {
    if (!effectiveDomainIds.add(domainId)) {
      return;
    }

    final ManifestsDomain domain = configuredDomainsById.get(domainId);
    if (domain == null) {
      throw new IllegalArgumentException(
          "Unknown manifest domain in policy resolution: " + domainId);
    }

    for (String dependencyDomainId : domain.dependsOnDomainIds()) {
      collectDomainDependencies(dependencyDomainId, configuredDomainsById, effectiveDomainIds);
    }
  }

  void enforceLiteralBlockStyleForConfigMapScripts(Path synthesizedFile) {
    final List<Map<String, Object>> documents =
        yaml.read(synthesizedFile).as(DOCUMENT_TYPE).map(this::normalizeConfigMapScripts).toList();
    yaml.write(synthesizedFile).documents(documents);
  }

  Map<String, Object> normalizeConfigMapScripts(Map<String, Object> document) {
    if (!"ConfigMap".equals(document.get("kind"))) {
      return document;
    }
    final Object data = document.get("data");
    if (!(data instanceof Map<?, ?> dataMap)) {
      return document;
    }

    final LinkedHashMap<String, Object> rewrittenData = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : dataMap.entrySet()) {
      final Object key = entry.getKey();
      final Object value = entry.getValue();
      if (key instanceof String dataKey
          && value instanceof String textValue
          && isScriptLikeConfigMapKey(dataKey)) {
        rewrittenData.put(dataKey, normalizeScriptConfigMapText(textValue));
      } else if (key instanceof String dataKey) {
        rewrittenData.put(dataKey, value);
      }
    }
    document.put("data", rewrittenData);
    return document;
  }

  boolean isScriptLikeConfigMapKey(String dataKey) {
    final String key = dataKey.toLowerCase(Locale.ROOT);
    return SCRIPT_DATA_SUFFIXES.stream().anyMatch(key::endsWith) || key.contains("script");
  }

  String normalizeScriptConfigMapText(String textValue) {
    String normalized = textValue.replace("\r\n", "\n").replace("\r", "\n");
    if (!normalized.contains("\n") && normalized.contains("\\n")) {
      normalized = normalized.replace("\\r\\n", "\n").replace("\\n", "\n");
    }
    if (!normalized.endsWith("\n")) {
      normalized = normalized + "\n";
    }
    return normalized;
  }
}
