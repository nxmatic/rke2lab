package io.nxmatic.rke2lab.manifests.systemd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import io.nxmatic.rke2lab.systemd.cdk8s.SystemdChart;
import io.nxmatic.rke2lab.systemd.cdk8s.SystemdScript;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Carries the systemd bundle as local-config ConfigMap dotfiles so {@code manifests.d/systemd}
 * holds ONLY manifests — never raw {@code .service}/{@code .sh}. This is decision (X): the {@link
 * SystemdChart} OWNS the scripts too — {@link #synthesize} registers the bundled host scripts onto
 * the chart, then renders the whole bundle (units + drop-ins, and scripts) into TWO hidden
 * ConfigMap dotfiles the incus host materializer extracts back to files.
 *
 * <p>No archive/base64/checksums (the daemonset needs those only to beat the 1MiB limit of a REAL
 * k8s ConfigMap): these are local-config, never applied to k8s, materialised disk-to-disk in-JVM —
 * so plain {@code data} suffices. Deterministic (sorted keys, fixed envelope order) so the staging
 * checksum is stable across identical runs.
 */
public final class SystemdBundleConfigMaps {

  public static final String UNITS_DOTFILE = ".configmap-systemd-units.yml";
  public static final String SCRIPTS_DOTFILE = ".configmap-systemd-scripts.yml";

  private static final ObjectMapper YAML =
      new ObjectMapper(
          new YAMLFactory()
              .enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE)
              .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER));

  private SystemdBundleConfigMaps() {}

  /**
   * Register the bundled host scripts onto the chart (so it owns the coupled unit+script bundle),
   * then write the units and scripts bundles as two ConfigMap dotfiles under {@code systemdDir}.
   */
  public static void synthesize(SystemdChart chart, Path systemdDir) throws IOException {
    SystemdScriptResources.load()
        .forEach((name, content) -> new SystemdScript(chart, name, name, content));
    Files.createDirectories(systemdDir);
    writeConfigMap(
        systemdDir.resolve(UNITS_DOTFILE), "rke2lab-systemd-units", chart.renderUnitFiles());
    writeConfigMap(
        systemdDir.resolve(SCRIPTS_DOTFILE), "rke2lab-systemd-scripts", chart.renderScriptFiles());
  }

  private static void writeConfigMap(Path file, String name, Map<String, String> data)
      throws IOException {
    final Map<String, Object> configMap = new LinkedHashMap<>();
    configMap.put("apiVersion", "v1");
    configMap.put("kind", "ConfigMap");
    configMap.put("metadata", Map.of("name", name));
    configMap.put("data", new TreeMap<>(data));
    YAML.writeValue(file.toFile(), configMap);
  }
}
