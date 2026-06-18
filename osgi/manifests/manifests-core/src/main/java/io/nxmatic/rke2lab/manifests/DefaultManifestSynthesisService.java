package io.nxmatic.rke2lab.manifests;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MappingIterator;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdChart;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdDropIn;
import io.nxmatic.rke2lab.cdk8s.systemd.SystemdTarget;
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
import io.nxmatic.rke2lab.manifests.systemd.BootstrapInfrastructureSynthesizer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.cdk8s.App;
import org.cdk8s.AppProps;
import org.cdk8s.Chart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Default SPI implementation for canonical manifest synthesis. */
public final class DefaultManifestSynthesisService implements ManifestSynthesisService {

  static final Logger LOG = LoggerFactory.getLogger(DefaultManifestSynthesisService.class);

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

    final ManifestSynthesisContext context =
        ManifestSynthesisContext.of(
            request.floxDebugPolicy(),
            request.bootstrapIdentity(),
            request.networkTopology(),
            request.componentVersions(),
            request.imageState());
    try (var ignored = ManifestSynthesisContext.bind(context)) {
      return synthesizeInContext(request);
    }
  }

  ManifestSynthesisResult synthesizeInContext(ManifestSynthesisRequest request) throws IOException {
    // Local pipeline for the manifest synthesis workflow (fluent grammar, see
    // docs/fluent-pipeline-grammar.adoc).
    // Structure: setup → registry → targets → units → finalization → synthesis
    final class SynthesisPipeline {
      final ManifestSynthesisRequest request;

      SynthesisPipeline(ManifestSynthesisRequest request) {
        this.request = request;
      }

      AwaitingOnFailure onFailure(OnFailure handler) {
        return new AwaitingOnFailure(new State(request, handler));
      }

      final class State {
        final ManifestSynthesisRequest request;
        final OnFailure onFailure;

        App app;
        Chart chart;
        SystemdChart systemdChart;
        Path synthOutdir;
        Path synthManifestFile;
        Path systemdOutdir;

        ManifestsDomainRegistry domainRegistry;
        int manifestUnitHitCount;

        ManifestDomainCatalog domainCatalog;

        SystemdTarget rke2labTarget;
        SystemdTarget networkTarget;
        SystemdTarget toolsTarget;
        SystemdTarget bootstrapTarget;
        SystemdTarget manifestsTarget;
        SystemdTarget cniManifestsTarget;
        SystemdTarget operatorManifestsTarget;
        SystemdTarget secretsTarget;
        SystemdSynthesisContext systemdContext;

        State(ManifestSynthesisRequest request, OnFailure onFailure) {
          this.request = request;
          this.onFailure = onFailure;
        }
      }

      final class AwaitingOnFailure {
        final State state;

        AwaitingOnFailure(State state) {
          this.state = state;
        }

        Cdk8sSetupDone during(
            String topic, java.util.function.Function<Cdk8sSetupStage, Cdk8sSetupStage> body) {
          final Cdk8sSetupStage stage = new Cdk8sSetupStage(state);
          runDuring("manifest-synthesis", topic, stage, body, state.onFailure);
          return new Cdk8sSetupDone(state);
        }
      }

      final class Cdk8sSetupStage {
        final State state;

        Cdk8sSetupStage(State state) {
          this.state = state;
        }

        Cdk8sSetupStage createChartsAndPaths() {
          state.synthOutdir = state.request.synthOutdir();
          state.synthManifestFile = state.request.synthManifestFile();
          state.systemdOutdir = state.synthOutdir.resolve("systemd");

          state.app = new App(AppProps.builder().outdir(state.synthOutdir.toString()).build());
          state.chart = new Chart(state.app, "manifests");
          state.systemdChart = new SystemdChart(state.app, "systemd");

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
            String topic,
            java.util.function.Function<DomainRegistryStage, DomainRegistryStage> body) {
          final DomainRegistryStage stage = new DomainRegistryStage(state);
          runDuring("manifest-synthesis", topic, stage, body, state.onFailure);
          return new DomainRegistryDone(state);
        }
      }

      final class DomainRegistryStage {
        final State state;

        DomainRegistryStage(State state) {
          this.state = state;
        }

        DomainRegistryStage buildAndApplyUnits() {
          final ManifestsDomainRegistry configuredDomainRegistry =
              buildDomainRegistry(state.request.manifestDomainPolicy().orElse(null));

          state.domainRegistry = applyManifestDomainPolicy(state.request, configuredDomainRegistry);

          final Cdk8sApiObjectResolver resolver = new Cdk8sApiObjectResolver(state.chart);
          final ManifestsUnitVisitor manifestUnitVisitor = new ApplyingManifestsUnitVisitor();

          LOG.info("Configured {} manifest domains", state.domainRegistry.domains().size());
          LOG.debug(
              "Manifest domains: {}",
              state.domainRegistry.domains().stream()
                  .map(ManifestsDomain::domainId)
                  .sorted()
                  .toList());

          final CoherentManifestsDomainRegistry coherent = state.domainRegistry.resolve();

          state.manifestUnitHitCount = 0;
          for (ManifestsUnit manifestUnit : coherent.visitOrder()) {
            state.manifestUnitHitCount++;
            final String manifestUnitId = manifestUnit.manifestUnitId();
            LOG.debug("Applying manifest unit '{}'", manifestUnitId);
            final String domainId = coherent.requireDomainIdForManifestsUnit(manifestUnitId);
            manifestUnitVisitor.visit(
                manifestUnit,
                new ManifestsUnitContext(state.chart, domainId, manifestUnitId, resolver));
          }

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
            String topic,
            java.util.function.Function<SystemdTargetsStage, SystemdTargetsStage> body) {
          final SystemdTargetsStage stage = new SystemdTargetsStage(state);
          runDuring("manifest-synthesis", topic, stage, body, state.onFailure);
          return new SystemdTargetsDone(state);
        }
      }

      final class SystemdTargetsStage {
        final State state;

        SystemdTargetsStage(State state) {
          this.state = state;
        }

        SystemdTargetsStage createTargetHierarchy() {
          state.domainCatalog =
              ManifestDomainCatalog.builder()
                  .addDefaultDomains()
                  .addDefaultStageALinkableDomains()
                  .build();

          LOG.debug("Creating systemd targets");
          state.rke2labTarget =
              new SystemdTarget(state.systemdChart, "rke2lab")
                  .description("RKE2 Lab Bootstrap Target")
                  .documentation("https://github.com/nxmatic/rke2lab")
                  .wantedBy("multi-user.target");

          state.networkTarget =
              new SystemdTarget(state.systemdChart, "rke2lab-network")
                  .description("RKE2 Lab Network Infrastructure Target")
                  .after("network-online.target")
                  .wants("network-online.target")
                  .partOf(state.rke2labTarget.getUnitFileName())
                  .wantedBy(state.rke2labTarget.getUnitFileName());

          state.toolsTarget =
              new SystemdTarget(state.systemdChart, "rke2lab-tools")
                  .description("RKE2 Lab Tools and Utilities Target")
                  .after(state.networkTarget.getUnitFileName())
                  .wants(state.networkTarget.getUnitFileName())
                  .partOf(state.rke2labTarget.getUnitFileName())
                  .wantedBy(state.rke2labTarget.getUnitFileName());

          state.bootstrapTarget =
              new SystemdTarget(state.systemdChart, "rke2lab-bootstrap")
                  .description("RKE2 Lab Early Bootstrap (pre-server)")
                  .after(state.networkTarget.getUnitFileName(), state.toolsTarget.getUnitFileName())
                  .requires(
                      state.networkTarget.getUnitFileName(), state.toolsTarget.getUnitFileName())
                  .partOf(state.rke2labTarget.getUnitFileName())
                  .wantedBy(state.rke2labTarget.getUnitFileName())
                  .also(state.networkTarget.getUnitFileName(), state.toolsTarget.getUnitFileName());

          state.manifestsTarget =
              new SystemdTarget(state.systemdChart, "rke2lab-manifests")
                  .description("RKE2 Lab Manifest Installers (post-server)")
                  .after(state.bootstrapTarget.getUnitFileName(), "rke2-server.service")
                  .requires("rke2-server.service")
                  .partOf(state.rke2labTarget.getUnitFileName())
                  .wantedBy("rke2-server.service");

          state.cniManifestsTarget =
              new SystemdTarget(state.systemdChart, "rke2lab-cni-manifests")
                  .description("RKE2 Lab Manifest Installers (post-CNI-ready)")
                  .after(state.manifestsTarget.getUnitFileName(), "rke2-server.service")
                  .requires("rke2-server.service")
                  .partOf(state.rke2labTarget.getUnitFileName())
                  .wantedBy(state.rke2labTarget.getUnitFileName());

          state.operatorManifestsTarget =
              new SystemdTarget(state.systemdChart, "rke2lab-operator-manifests")
                  .description("RKE2 Lab Manifest Installers (post-operator-ready)")
                  .after(state.cniManifestsTarget.getUnitFileName(), "rke2-server.service")
                  .requires("rke2-server.service")
                  .partOf(state.rke2labTarget.getUnitFileName())
                  .wantedBy(state.rke2labTarget.getUnitFileName());

          state.secretsTarget =
              new SystemdTarget(state.systemdChart, "rke2lab-secrets")
                  .description("RKE2 Lab Secrets Installers (post-server)")
                  .after(
                      state.bootstrapTarget.getUnitFileName(),
                      state.manifestsTarget.getUnitFileName(),
                      "rke2-server.service")
                  .requires("rke2-server.service")
                  .partOf(state.rke2labTarget.getUnitFileName())
                  .wantedBy(state.rke2labTarget.getUnitFileName());

          state.systemdContext =
              SystemdSynthesisContext.builder()
                  .rke2labTarget(state.rke2labTarget)
                  .bootstrapTarget(state.bootstrapTarget)
                  .manifestsTarget(state.manifestsTarget)
                  .cniManifestsTarget(state.cniManifestsTarget)
                  .operatorManifestsTarget(state.operatorManifestsTarget)
                  .secretsTarget(state.secretsTarget)
                  .networkTarget(state.networkTarget)
                  .toolsTarget(state.toolsTarget)
                  .domainCatalog(state.domainCatalog)
                  .build();

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

        SystemdUnitsDone during(
            String topic, java.util.function.Function<SystemdUnitsStage, SystemdUnitsStage> body) {
          final SystemdUnitsStage stage = new SystemdUnitsStage(state);
          runDuring("manifest-synthesis", topic, stage, body, state.onFailure);
          return new SystemdUnitsDone(state);
        }
      }

      final class SystemdUnitsStage {
        final State state;

        SystemdUnitsStage(State state) {
          this.state = state;
        }

        SystemdUnitsStage synthesizeInfrastructureAndDomains() {
          LOG.debug("Synthesizing bootstrap and infrastructure systemd units");
          new BootstrapInfrastructureSynthesizer(state.systemdChart, state.systemdContext)
              .synthesizeAll();

          for (ManifestsDomain domain : state.domainRegistry.domains()) {
            LOG.debug("Synthesizing systemd units for domain '{}'", domain.domainId());
            domain.synthesizeSystemdUnits(state.systemdChart, state.systemdContext);
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
            String topic,
            java.util.function.Function<TargetFinalizationStage, TargetFinalizationStage> body) {
          final TargetFinalizationStage stage = new TargetFinalizationStage(state);
          runDuring("manifest-synthesis", topic, stage, body, state.onFailure);
          return new TargetFinalizationDone(state);
        }
      }

      final class TargetFinalizationStage {
        final State state;

        TargetFinalizationStage(State state) {
          this.state = state;
        }

        TargetFinalizationStage finalizeAndCreateDropIn() {
          LOG.debug("Configuring main rke2lab.target dependencies");
          state
              .rke2labTarget
              .after(
                  state.networkTarget.getUnitFileName(),
                  state.toolsTarget.getUnitFileName(),
                  state.bootstrapTarget.getUnitFileName(),
                  "rke2-server.service")
              .wants(
                  state.networkTarget.getUnitFileName(),
                  state.toolsTarget.getUnitFileName(),
                  state.bootstrapTarget.getUnitFileName(),
                  state.manifestsTarget.getUnitFileName(),
                  state.cniManifestsTarget.getUnitFileName(),
                  state.operatorManifestsTarget.getUnitFileName(),
                  state.secretsTarget.getUnitFileName(),
                  "rke2-server.service");

          LOG.debug("Finalizing systemd target dependencies");
          state.systemdChart.finalizeTargetDependencies();

          LOG.debug("Creating rke2-server.service drop-in for lifecycle hooks");
          new SystemdDropIn(state.systemdChart, "rke2lab-server-hooks", "rke2-server.service")
              .execStartPre("/srv/host/systemd-scripts.d/rke2lab-server-pre-start.sh")
              .execStartPost("/srv/host/systemd-scripts.d/rke2lab-server-post-start.sh")
              .wants(state.manifestsTarget.getUnitFileName());

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

        SynthesisDone during(
            String topic, java.util.function.Function<SynthesisStage, SynthesisStage> body) {
          final SynthesisStage stage = new SynthesisStage(state);
          runDuring("manifest-synthesis", topic, stage, body, state.onFailure);
          return new SynthesisDone(state);
        }
      }

      final class SynthesisStage {
        final State state;

        SynthesisStage(State state) {
          this.state = state;
        }

        SynthesisStage synthAndPostprocess() {
          try {
            LOG.info("Calling app.synth() to synthesize K8s manifests to: {}", state.synthOutdir);
            state.app.synth();
            LOG.info(
                "app.synth() completed, now synthesizing systemd units to: {}",
                state.systemdOutdir);
            state.systemdChart.synthesize(state.systemdOutdir);
            LOG.info("systemdChart.synthesize() completed");

            Path synthesizedFile = null;
            try (var files = Files.list(state.synthOutdir)) {
              synthesizedFile =
                  files
                      .filter(
                          p -> {
                            String name = p.getFileName().toString();
                            return name.endsWith("-manifests.k8s.yaml")
                                || name.equals("manifests.k8s.yaml");
                          })
                      .findFirst()
                      .orElse(null);
            }

            if (synthesizedFile == null || !Files.exists(synthesizedFile)) {
              throw new IllegalStateException(
                  "Expected synthesized manifest file (manifests.k8s.yaml or *-manifests.k8s.yaml) is missing in: "
                      + state.synthOutdir);
            }

            enforceLiteralBlockStyleForConfigMapScripts(synthesizedFile);

            Files.createDirectories(state.synthManifestFile.getParent());
            Files.move(
                synthesizedFile, state.synthManifestFile, StandardCopyOption.REPLACE_EXISTING);

            LOG.info(
                "Synthesized K8s manifests and systemd units from canonical manifest units (manifest unit hits={})",
                state.manifestUnitHitCount);

            return this;
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        }
      }

      final class SynthesisDone {
        final State state;

        SynthesisDone(State state) {
          this.state = state;
        }

        ManifestSynthesisResult complete() {
          return new ManifestSynthesisResult(
              state.synthManifestFile,
              state.systemdOutdir,
              state.manifestUnitHitCount,
              state.domainRegistry.domains().size());
        }
      }

      static <S> void runDuring(
          String scope,
          String topic,
          S stage,
          java.util.function.Function<S, S> body,
          OnFailure onFailure) {
        LOG.debug("→ entering {}", topic);
        final long start = System.nanoTime();
        try {
          body.apply(stage);
        } catch (Throwable cause) {
          onFailure.accept(topic, cause);
          throw new PipelineStageFailure(topic, cause);
        } finally {
          final long elapsed = System.nanoTime() - start;
          LOG.debug("← leaving {} (elapsed: {} ms)", topic, elapsed / 1_000_000);
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

  ManifestsDomainRegistry buildDomainRegistry(ManifestDomainPolicy policy) {
    final ManifestDomainPolicy effectivePolicy =
        policy != null ? policy : ManifestDomainPolicy.builder().build();

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

  void enforceLiteralBlockStyleForConfigMapScripts(Path synthesizedFile) throws IOException {
    final List<Map<String, Object>> documents = new ArrayList<>();
    try (MappingIterator<Map<String, Object>> iterator =
        ManifestYaml.readValues(synthesizedFile, DOCUMENT_TYPE)) {
      while (iterator.hasNext()) {
        final Map<String, Object> document = iterator.next();
        if (document != null) {
          documents.add(normalizeConfigMapScripts(document));
        }
      }
    }

    ManifestYaml.writeDocuments(synthesizedFile, documents);
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

  @FunctionalInterface
  interface OnFailure {
    void accept(String topic, Throwable cause);
  }

  static final class PipelineStageFailure extends RuntimeException {
    final String topic;

    PipelineStageFailure(String topic, Throwable cause) {
      super("Pipeline stage '" + topic + "' failed: " + cause.getMessage(), cause);
      this.topic = topic;
    }

    String topic() {
      return topic;
    }
  }
}
