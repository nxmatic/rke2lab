package io.nxmatic.rk2lab.manifests;

import io.nxmatic.rk2lab.manifests.api.ManifestDomainPolicy;
import io.nxmatic.rk2lab.manifests.api.ManifestSynthesisRequest;
import io.nxmatic.rk2lab.manifests.api.ManifestSynthesisResult;
import io.nxmatic.rk2lab.manifests.api.ManifestSynthesisService;
import io.nxmatic.rk2lab.manifests.layers.cicd.CicdDomainRegistrar;
import io.nxmatic.rk2lab.manifests.layers.cluster.ClusterDomainRegistrar;
import io.nxmatic.rk2lab.manifests.layers.common.ApplyingManifestUnitVisitor;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomain;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomainRegistry;
import io.nxmatic.rk2lab.manifests.layers.common.LayerDomainRegistryBuilder;
import io.nxmatic.rk2lab.manifests.layers.common.ManifestSynthesisContext;
import io.nxmatic.rk2lab.manifests.layers.common.ManifestUnit;
import io.nxmatic.rk2lab.manifests.layers.common.ManifestUnitDependencyApplier;
import io.nxmatic.rk2lab.manifests.layers.common.ManifestUnitRegistry;
import io.nxmatic.rk2lab.manifests.layers.common.ManifestUnitVisitor;
import io.nxmatic.rk2lab.manifests.layers.common.registry.ManifestAssemblyRegistry;
import io.nxmatic.rk2lab.manifests.layers.gitops.GitopsDomainRegistrar;
import io.nxmatic.rk2lab.manifests.layers.ha.HighAvailabilityDomainRegistrar;
import io.nxmatic.rk2lab.manifests.layers.mesh.MeshDomainRegistrar;
import io.nxmatic.rk2lab.manifests.layers.networking.NetworkingDomainRegistrar;
import io.nxmatic.rk2lab.manifests.layers.replication.ReplicationDomainRegistrar;
import io.nxmatic.rk2lab.manifests.layers.runtime.RuntimeDomainRegistrar;
import io.nxmatic.rk2lab.manifests.layers.storage.StorageDomainRegistrar;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.cdk8s.App;
import org.cdk8s.AppProps;
import org.cdk8s.Chart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Represent;
import org.yaml.snakeyaml.representer.Representer;

/** Default SPI implementation for canonical manifest synthesis. */
public final class DefaultManifestSynthesisService implements ManifestSynthesisService {

  private static final Logger LOG = LoggerFactory.getLogger(DefaultManifestSynthesisService.class);

  private static final Set<String> SCRIPT_DATA_SUFFIXES =
      Set.of(".sh", ".bash", ".env", ".yaml", ".yml", ".conf", ".policy");

  private static final Pattern QUOTED_SCALAR_LINE_PATTERN =
      Pattern.compile("^(\\s*)([A-Za-z0-9._-]+):\\s*\"(.*)\"\\s*$");

  @Override
  public String providerId() {
    return "default-cdk8s-synthesizer";
  }

  @Override
  public ManifestSynthesisResult synthesize(ManifestSynthesisRequest request) throws IOException {
    LOG.info(
        "Starting manifests synthesis via provider '{}' (floxDebugPolicy.enabled={})",
        providerId(),
        request.floxDebugPolicy().enabled());

    final ManifestSynthesisContext context = ManifestSynthesisContext.of(request.floxDebugPolicy());
    try (var ignored = ManifestSynthesisContext.bind(context)) {
      return synthesizeInContext(request);
    }
  }

  private ManifestSynthesisResult synthesizeInContext(ManifestSynthesisRequest request)
      throws IOException {
    final Path synthOutdir = request.synthOutdir();
    final Path synthManifestFile = request.synthManifestFile();

    final App app = new App(AppProps.builder().outdir(synthOutdir.toString()).build());
    final Chart chart = new Chart(app, "manifests");

    final LayerDomainRegistry configuredDomainRegistry =
        new LayerDomainRegistryBuilder()
            .register(new ClusterDomainRegistrar())
            .register(new StorageDomainRegistrar())
            .register(new ReplicationDomainRegistrar())
            .register(new GitopsDomainRegistrar())
            .register(new RuntimeDomainRegistrar())
            .register(new NetworkingDomainRegistrar())
            .register(new MeshDomainRegistrar())
            .register(new HighAvailabilityDomainRegistrar())
            .register(new CicdDomainRegistrar())
            .build();

    final LayerDomainRegistry domainRegistry =
        applyManifestDomainPolicy(request, configuredDomainRegistry);

    final List<ManifestUnit> manifestUnits =
        domainRegistry.manifestUnits().stream()
            .sorted(Comparator.comparing(ManifestUnit::manifestUnitId))
            .toList();

    final ManifestAssemblyRegistry assemblyRegistry = new ManifestAssemblyRegistry();
    final ManifestUnitRegistry manifestUnitRegistry = new ManifestUnitRegistry(manifestUnits);
    final ManifestUnitVisitor manifestUnitVisitor = new ApplyingManifestUnitVisitor();
    final ManifestUnitDependencyApplier dependencyApplier =
        new ManifestUnitDependencyApplier(
            domainRegistry, manifestUnitRegistry, manifestUnitVisitor, chart, assemblyRegistry);

    LOG.info("Configured {} manifest domains", domainRegistry.domains().size());
    LOG.debug(
        "Manifest domains: {}",
        domainRegistry.domains().stream().map(domain -> domain.domainId()).sorted().toList());

    int manifestUnitHitCount = 0;
    for (ManifestUnit manifestUnit : manifestUnits) {
      manifestUnitHitCount++;
      LOG.debug("Applying manifest unit '{}'", manifestUnit.manifestUnitId());
      domainRegistry.applyManifestUnitWithDomainDependencies(
          manifestUnit.manifestUnitId(), dependencyApplier);
    }

    app.synth();

    final Path synthesizedFile = synthOutdir.resolve("manifests.k8s.yaml");
    if (!Files.exists(synthesizedFile)) {
      throw new IllegalStateException(
          "Expected synthesized manifest file is missing: " + synthesizedFile);
    }

    enforceLiteralBlockStyleForConfigMapScripts(synthesizedFile);

    Files.createDirectories(synthManifestFile.getParent());
    Files.move(synthesizedFile, synthManifestFile, StandardCopyOption.REPLACE_EXISTING);

    LOG.info(
        "Synthesized manifests from canonical manifest units (manifest unit hits={})",
        manifestUnitHitCount);

    return new ManifestSynthesisResult(
        synthManifestFile, manifestUnitHitCount, domainRegistry.domains().size());
  }

  private LayerDomainRegistry applyManifestDomainPolicy(
      ManifestSynthesisRequest request, LayerDomainRegistry configuredDomainRegistry) {
    if (request.manifestDomainPolicy().isEmpty()) {
      return configuredDomainRegistry;
    }

    final ManifestDomainPolicy manifestDomainPolicy = request.manifestDomainPolicy().orElseThrow();
    final Map<String, LayerDomain> configuredDomainsById =
        configuredDomainRegistry.domains().stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    LayerDomain::domainId,
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

    final List<LayerDomain> filteredDomains =
        configuredDomainRegistry.domains().stream()
            .filter(domain -> effectiveDomainIds.contains(domain.domainId()))
            .toList();
    return new LayerDomainRegistry(filteredDomains);
  }

  private void collectDomainDependencies(
      String domainId,
      Map<String, LayerDomain> configuredDomainsById,
      Set<String> effectiveDomainIds) {
    if (!effectiveDomainIds.add(domainId)) {
      return;
    }

    final LayerDomain domain = configuredDomainsById.get(domainId);
    if (domain == null) {
      throw new IllegalArgumentException(
          "Unknown manifest domain in policy resolution: " + domainId);
    }

    for (String dependencyDomainId : domain.dependsOnDomainIds()) {
      collectDomainDependencies(dependencyDomainId, configuredDomainsById, effectiveDomainIds);
    }
  }

  private void enforceLiteralBlockStyleForConfigMapScripts(Path synthesizedFile)
      throws IOException {
    final String yamlSource = Files.readString(synthesizedFile);
    final Iterable<Object> loadedDocuments =
        new Yaml(new SafeConstructor(largeDocumentLoaderOptions())).loadAll(yamlSource);
    final List<Object> documents = new java.util.ArrayList<>();
    for (Object loadedDocument : loadedDocuments) {
      documents.add(applyConfigMapScriptLiteralBlocks(loadedDocument));
    }

    final DumperOptions dumperOptions = new DumperOptions();
    dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    dumperOptions.setSplitLines(false);
    dumperOptions.setPrettyFlow(true);
    dumperOptions.setIndent(2);

    final Representer representer = new LiteralBlockRepresenter(dumperOptions);

    final Yaml yaml = new Yaml(representer, dumperOptions);
    final StringWriter writer = new StringWriter();
    yaml.dumpAll(documents.iterator(), writer);
    final String normalizedYaml = coerceQuotedScriptScalarsToLiteralBlocks(writer.toString());
    Files.writeString(synthesizedFile, normalizedYaml);
  }

  /**
   * SnakeYaml caps single-document parses at 3 MiB by default. The synthesized manifest carries the
   * base64-encoded NRI plugin archive plus the flox installer-assets ConfigMap (now including
   * pre-locked flake/manifest locks per env), so single ConfigMap documents can sit well above
   * that. Bump to 64 MiB — generous headroom for the inputs we actually emit.
   */
  private static LoaderOptions largeDocumentLoaderOptions() {
    final LoaderOptions options = new LoaderOptions();
    options.setCodePointLimit(64 * 1024 * 1024);
    return options;
  }

  private String coerceQuotedScriptScalarsToLiteralBlocks(String yamlText) {
    final StringBuilder rewritten = new StringBuilder(yamlText.length());
    final String[] lines = yamlText.split("\\n", -1);
    for (String line : lines) {
      final Matcher matcher = QUOTED_SCALAR_LINE_PATTERN.matcher(line);
      if (!matcher.matches()) {
        rewritten.append(line).append('\n');
        continue;
      }

      final String indent = matcher.group(1);
      final String key = matcher.group(2);
      final String value = matcher.group(3);
      if (!isScriptLikeConfigMapKey(key) || !value.contains("\\n")) {
        rewritten.append(line).append('\n');
        continue;
      }

      final String decoded = normalizeScriptConfigMapText(decodeEscapedQuotedScalar(value));
      rewritten.append(indent).append(key).append(": |\n");
      final String blockIndent = indent + "  ";
      final String[] blockLines = decoded.split("\\n", -1);
      for (int i = 0; i < blockLines.length; i++) {
        final String blockLine = blockLines[i];
        if (i == blockLines.length - 1 && blockLine.isEmpty()) {
          continue;
        }
        rewritten.append(blockIndent).append(blockLine).append('\n');
      }
    }
    return rewritten.toString();
  }

  private String decodeEscapedQuotedScalar(String escaped) {
    final StringBuilder decoded = new StringBuilder(escaped.length());
    for (int i = 0; i < escaped.length(); i++) {
      final char ch = escaped.charAt(i);
      if (ch != '\\' || i + 1 >= escaped.length()) {
        decoded.append(ch);
        continue;
      }

      final char next = escaped.charAt(++i);
      switch (next) {
        case 'n' -> decoded.append('\n');
        case 'r' -> decoded.append('\r');
        case 't' -> decoded.append('\t');
        case '"' -> decoded.append('"');
        case '\\' -> decoded.append('\\');
        default -> decoded.append(next);
      }
    }
    return decoded.toString();
  }

  @SuppressWarnings("unchecked")
  private Object applyConfigMapScriptLiteralBlocks(Object document) {
    if (document instanceof List<?> list) {
      return list.stream().map(this::applyConfigMapScriptLiteralBlocks).toList();
    }
    if (!(document instanceof Map<?, ?> map)) {
      return document;
    }

    final LinkedHashMap<Object, Object> rewritten = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      rewritten.put(entry.getKey(), applyConfigMapScriptLiteralBlocks(entry.getValue()));
    }

    final Object kind = rewritten.get("kind");
    if (!"ConfigMap".equals(kind)) {
      return rewritten;
    }

    final Object data = rewritten.get("data");
    if (!(data instanceof Map<?, ?> dataMap)) {
      return rewritten;
    }

    final LinkedHashMap<Object, Object> rewrittenData = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : dataMap.entrySet()) {
      final Object key = entry.getKey();
      final Object value = entry.getValue();
      if (key instanceof String dataKey
          && value instanceof String textValue
          && isScriptLikeConfigMapKey(dataKey)) {
        final String normalized = normalizeScriptConfigMapText(textValue);
        if (shouldRenderAsLiteralBlock(dataKey, normalized)) {
          rewrittenData.put(dataKey, new LiteralBlockString(normalized));
          continue;
        }
      }
      if (key instanceof String dataKey
          && value instanceof String textValue
          && shouldRenderAsLiteralBlock(dataKey, textValue)) {
        rewrittenData.put(dataKey, new LiteralBlockString(textValue));
      } else {
        rewrittenData.put(key, value);
      }
    }
    rewritten.put("data", rewrittenData);
    return rewritten;
  }

  private boolean shouldRenderAsLiteralBlock(String dataKey, String textValue) {
    return textValue.contains("\n") && isScriptLikeConfigMapKey(dataKey);
  }

  private boolean isScriptLikeConfigMapKey(String dataKey) {
    final String key = dataKey.toLowerCase(java.util.Locale.ROOT);
    return SCRIPT_DATA_SUFFIXES.stream().anyMatch(key::endsWith) || key.contains("script");
  }

  private String normalizeScriptConfigMapText(String textValue) {
    String normalized = textValue.replace("\r\n", "\n").replace("\r", "\n");
    if (!normalized.contains("\n") && normalized.contains("\\n")) {
      normalized = normalized.replace("\\r\\n", "\n").replace("\\n", "\n");
    }
    if (!normalized.endsWith("\n")) {
      normalized = normalized + "\n";
    }
    return normalized;
  }

  private static final class LiteralBlockString {
    private final String value;

    private LiteralBlockString(String value) {
      this.value = value;
    }

    @Override
    public String toString() {
      return value;
    }
  }

  private static final class LiteralBlockRepresenter extends Representer {
    private LiteralBlockRepresenter(DumperOptions dumperOptions) {
      super(dumperOptions);
      this.addClassTag(LiteralBlockString.class, Tag.STR);
      this.representers.put(
          LiteralBlockString.class,
          new Represent() {
            @Override
            public org.yaml.snakeyaml.nodes.Node representData(Object data) {
              return representScalar(Tag.STR, data.toString(), DumperOptions.ScalarStyle.LITERAL);
            }
          });
    }
  }
}
