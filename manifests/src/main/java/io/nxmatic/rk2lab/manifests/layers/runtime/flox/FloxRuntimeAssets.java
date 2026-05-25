// @codebase
package io.nxmatic.rk2lab.manifests.layers.runtime.flox;

import io.nxmatic.rk2lab.manifests.layers.runtime.daemonset.RuntimeDaemonsetScriptPolicyAssets;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

public final class FloxRuntimeAssets {

  private static final String FLOX_RESOURCE_ROOT = "/runtime/flox-runtime";
  private static final String ENV_RESOURCE_ROOT = FLOX_RESOURCE_ROOT + "/env.d";

  /**
   * Per-env files that live in the resource tree. Everything else (env.json, .gitattributes,
   * .gitignore) is uniform boilerplate and is synthesized in code so the resource tree only carries
   * the parts that genuinely differ between envs.
   */
  private static final String FLAKE_NIX_RESOURCE = "flake.nix";

  private static final String MANIFEST_TOML_RESOURCE = "manifest.toml";

  /**
   * Static .gitignore content placed inside {@code .flox/env/} — the path-flake root. The {@code
   * *.lock} rule keeps nix's path-flake fetcher hash stable across the lock→realise phases of
   * {@code flox activate}: flox writes {@code manifest.lock} after computing the path's narHash,
   * which would otherwise drift the hash and trigger {@code flake.cc:37} assertion failures during
   * realise. The activation step initializes {@code .flox/env/} as a git repo so this rule is
   * honored by nix's gitignore-aware path-flake enumeration.
   */
  private static final String GITIGNORE_CONTENT = "*.lock\n";

  private final Class<?> resourceAnchor;
  private final List<InstallerAsset> floxInstallerConfigMapAssets;
  private final RuntimeDaemonsetScriptPolicyAssets runtimeDaemonsetScriptPolicyAssets;
  private final NriPluginArchiveAssets nriPluginArchiveAssets;

  private FloxRuntimeAssets(Builder builder) {
    this.resourceAnchor = builder.resourceAnchor;
    this.floxInstallerConfigMapAssets = List.copyOf(builder.floxInstallerConfigMapAssets);
    this.runtimeDaemonsetScriptPolicyAssets = builder.runtimeDaemonsetScriptPolicyAssets;
    this.nriPluginArchiveAssets = builder.nriPluginArchiveAssets;
  }

  public static Builder builder() {
    return new Builder();
  }

  public Map<String, String> installerConfigMapData() {
    final LinkedHashMap<String, String> data = new LinkedHashMap<>();
    for (InstallerAsset asset : floxInstallerConfigMapAssets) {
      data.put(asset.configMapKey(), normalizeConfigMapText(resolveAssetContent(asset)));
    }
    data.putAll(runtimeDaemonsetScriptPolicyAssets.configMapData());
    data.put(NriPluginArchiveAssets.ARCHIVE_CONFIGMAP_KEY, nriPluginArchiveAssets.archiveBase64());
    data.put(NriPluginArchiveAssets.MANIFEST_CONFIGMAP_KEY, nriPluginArchiveAssets.manifestJson());
    return Map.copyOf(data);
  }

  public Object[] installerVolumeItems() {
    final LinkedHashMap<String, String> archiveItem =
        new LinkedHashMap<>(
            Map.of(
                "key",
                NriPluginArchiveAssets.ARCHIVE_CONFIGMAP_KEY,
                "path",
                "build-assets/" + NriPluginArchiveAssets.ARCHIVE_CONFIGMAP_KEY));
    final LinkedHashMap<String, String> manifestItem =
        new LinkedHashMap<>(
            Map.of(
                "key",
                NriPluginArchiveAssets.MANIFEST_CONFIGMAP_KEY,
                "path",
                "build-assets/" + NriPluginArchiveAssets.MANIFEST_CONFIGMAP_KEY));

    final List<Map<String, String>> items =
        floxInstallerConfigMapAssets.stream()
            .map(asset -> Map.of("key", asset.configMapKey(), "path", asset.mountPath()))
            .toList();

    final List<Map<String, String>> daemonsetItems =
        runtimeDaemonsetScriptPolicyAssets.relativePathsByKey().entrySet().stream()
            .map(
                entry ->
                    Map.of(
                        "key", entry.getKey(), "path", installerBuildAssetPath(entry.getValue())))
            .toList();

    final Object[] out = new Object[items.size() + daemonsetItems.size() + 2];
    for (int i = 0; i < items.size(); i++) {
      out[i] = items.get(i);
    }
    for (int i = 0; i < daemonsetItems.size(); i++) {
      out[items.size() + i] = daemonsetItems.get(i);
    }
    out[items.size() + daemonsetItems.size()] = archiveItem;
    out[items.size() + daemonsetItems.size() + 1] = manifestItem;
    return out;
  }

  private static String installerBuildAssetPath(String relativePath) {
    return "build-assets/" + relativePath;
  }

  private String resolveAssetContent(InstallerAsset asset) {
    if (asset.inlineContent() != null) {
      return asset.inlineContent();
    }
    return readResource(asset.classpathResource());
  }

  private String readResource(String resourcePath) {
    try (InputStream input = resourceAnchor.getResourceAsStream(resourcePath)) {
      if (input == null) {
        throw new IllegalStateException("Missing flox-runtime resource: " + resourcePath);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed reading flox-runtime resource: " + resourcePath, ex);
    }
  }

  private static String normalizeConfigMapText(String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }

    final String normalizedLineEndings = value.replace("\r\n", "\n").replace('\r', '\n');
    if (normalizedLineEndings.endsWith("\n")) {
      return normalizedLineEndings;
    }
    return normalizedLineEndings + "\n";
  }

  /**
   * A discovered Flox environment: the {@code category/name} pair (e.g. {@code networking/kdns})
   * derived from the {@code env.d/<category>/<name>/} directory layout on the classpath.
   */
  private record DiscoveredEnvironment(String category, String name) {
    String envPrefix() {
      return category + "/" + name;
    }

    String configMapPrefix() {
      return category + "-" + name + "-flox-env";
    }
  }

  /**
   * Pre-rendered {@code env.json} content. The logical name is hardcoded to {@code "default"} so
   * containers can activate via {@code flox activate --dir <path>} without each pod knowing the
   * env's category/name — the directory layout already disambiguates which env is mounted.
   */
  private static final String ENV_JSON_CONTENT = "{\"name\": \"default\", \"version\": 1}\n";

  /**
   * Walk the {@code /runtime/flox-runtime/env.d/} resource tree and return every {@code
   * category/name} directory that contains both {@code flake.nix} and {@code manifest.toml}. Works
   * whether resources sit on disk (during {@code mvn exec:java}) or inside a shaded JAR.
   */
  private static Set<DiscoveredEnvironment> discoverEnvironments(Class<?> resourceAnchor) {
    final TreeSet<DiscoveredEnvironment> discovered =
        new TreeSet<>(
            (a, b) -> {
              int cmp = a.category().compareTo(b.category());
              return cmp != 0 ? cmp : a.name().compareTo(b.name());
            });

    final URL rootUrl = resourceAnchor.getResource(ENV_RESOURCE_ROOT);
    if (rootUrl == null) {
      throw new IllegalStateException(
          "Flox env.d resource root not found on classpath: " + ENV_RESOURCE_ROOT);
    }

    try {
      final URLConnection connection = rootUrl.openConnection();
      if (connection instanceof JarURLConnection jarConnection) {
        addEnvsFromJar(jarConnection, discovered);
      } else {
        addEnvsFromFilesystem(rootUrl, discovered);
      }
    } catch (IOException ex) {
      throw new UncheckedIOException(
          "Failed scanning Flox env.d resource root: " + ENV_RESOURCE_ROOT, ex);
    }

    return discovered;
  }

  private static void addEnvsFromFilesystem(URL rootUrl, Set<DiscoveredEnvironment> sink)
      throws IOException {
    final Path rootDir;
    try {
      rootDir = Paths.get(rootUrl.toURI());
    } catch (java.net.URISyntaxException ex) {
      throw new IOException("Bad env.d URL: " + rootUrl, ex);
    }

    try (Stream<Path> categories = Files.list(rootDir)) {
      categories
          .filter(Files::isDirectory)
          .forEach(
              categoryDir -> {
                try (Stream<Path> names = Files.list(categoryDir)) {
                  names
                      .filter(Files::isDirectory)
                      .filter(name -> Files.isRegularFile(name.resolve(MANIFEST_TOML_RESOURCE)))
                      .filter(name -> Files.isRegularFile(name.resolve(FLAKE_NIX_RESOURCE)))
                      .forEach(
                          name ->
                              sink.add(
                                  new DiscoveredEnvironment(
                                      categoryDir.getFileName().toString(),
                                      name.getFileName().toString())));
                } catch (IOException ex) {
                  throw new UncheckedIOException(
                      "Failed listing flox env category: " + categoryDir, ex);
                }
              });
    }
  }

  private static void addEnvsFromJar(
      JarURLConnection jarConnection, Set<DiscoveredEnvironment> sink) throws IOException {
    final JarFile jarFile = jarConnection.getJarFile();
    // JAR entries are stored without a leading slash.
    final String prefix = ENV_RESOURCE_ROOT.substring(1) + "/"; // "runtime/flox-runtime/env.d/"
    final Set<String> manifestSeen = new LinkedHashSet<>();
    final Set<String> flakeSeen = new LinkedHashSet<>();
    final Enumeration<JarEntry> entries = jarFile.entries();
    while (entries.hasMoreElements()) {
      final JarEntry entry = entries.nextElement();
      final String name = entry.getName();
      if (!name.startsWith(prefix) || entry.isDirectory()) {
        continue;
      }
      final String tail = name.substring(prefix.length());
      final int firstSlash = tail.indexOf('/');
      if (firstSlash < 0) {
        continue;
      }
      final int secondSlash = tail.indexOf('/', firstSlash + 1);
      if (secondSlash < 0) {
        continue;
      }
      // Only consider files at exactly env.d/<category>/<name>/<file>.
      final String fileName = tail.substring(secondSlash + 1);
      if (fileName.indexOf('/') >= 0) {
        continue;
      }
      final String envKey = tail.substring(0, secondSlash);
      if (MANIFEST_TOML_RESOURCE.equals(fileName)) {
        manifestSeen.add(envKey);
      } else if (FLAKE_NIX_RESOURCE.equals(fileName)) {
        flakeSeen.add(envKey);
      }
    }
    for (String envKey : manifestSeen) {
      if (!flakeSeen.contains(envKey)) {
        continue;
      }
      final int slash = envKey.indexOf('/');
      sink.add(new DiscoveredEnvironment(envKey.substring(0, slash), envKey.substring(slash + 1)));
    }
  }

  public static final class Builder {
    private final Class<?> resourceAnchor = FloxRuntimeAssets.class;
    private final List<InstallerAsset> floxInstallerConfigMapAssets = new ArrayList<>();
    private RuntimeDaemonsetScriptPolicyAssets runtimeDaemonsetScriptPolicyAssets =
        RuntimeDaemonsetScriptPolicyAssets.builder().build();
    private final NriPluginArchiveAssets nriPluginArchiveAssets =
        NriPluginArchiveAssets.builder().build();

    private Builder() {
      addDiscoveredEnvironmentAssets();
      addDefaultCoreInstallerAssets();
      addDefaultDebugToolsInstallerAssets();
    }

    /**
     * Discover every {@code env.d/<category>/<name>/} directory on the classpath that has both
     * {@code flake.nix} and {@code manifest.toml}, then register the assets the host installer
     * needs.
     */
    private void addDiscoveredEnvironmentAssets() {
      for (DiscoveredEnvironment env : discoverEnvironments(resourceAnchor)) {
        addFloxEnvironmentAssets(env);
      }
    }

    /**
     * Register installer ConfigMap entries for one Flox environment. The {@code flake.nix} and
     * {@code manifest.toml} come from the classpath (the only per-env files in the resource tree);
     * the rest of the {@code .flox/} layout (env.json, .gitattributes, .gitignore) is synthesized
     * in code from the env's identity. Locks are intentionally omitted — the node regenerates them
     * via {@code flox activate}.
     */
    private void addFloxEnvironmentAssets(DiscoveredEnvironment env) {
      final String envPrefix = env.envPrefix();
      final String resourcePrefix = ENV_RESOURCE_ROOT + "/" + envPrefix;
      final String configMapPrefix = env.configMapPrefix();
      final String mountPrefix = "build-assets/env.d/" + envPrefix;

      // Per-env real files: classpath-backed.
      addInstallerAsset(
          configMapPrefix + "-flake-nix",
          resourcePrefix + "/" + FLAKE_NIX_RESOURCE,
          mountPrefix + "/.flox/env/flake.nix");
      addInstallerAsset(
          configMapPrefix + "-manifest-toml",
          resourcePrefix + "/" + MANIFEST_TOML_RESOURCE,
          mountPrefix + "/.flox/env/manifest.toml");

      // Synthesized boilerplate: identical structure across envs.
      addInstallerAsset(
          InstallerAsset.builder()
              .configMapKey(configMapPrefix + "-env-json")
              .inlineContent(ENV_JSON_CONTENT)
              .mountPath(mountPrefix + "/.flox/env.json")
              .build());
      addInstallerAsset(
          InstallerAsset.builder()
              .configMapKey(configMapPrefix + "-gitignore")
              .inlineContent(GITIGNORE_CONTENT)
              .mountPath(mountPrefix + "/.flox/env/.gitignore")
              .build());
    }

    private void addDefaultCoreInstallerAssets() {
      addInstallerAsset(
          "runtime-installer.sh",
          FLOX_RESOURCE_ROOT + "/runtime-installer.sh",
          "bin/runtime-installer.sh");
      addInstallerAsset(
          "nri-plugin-run.sh", FLOX_RESOURCE_ROOT + "/nri-plugin-run.sh", "bin/nri-plugin-run.sh");
      addInstallerAsset(
          "flox-nri-overlay-hook.sh",
          FLOX_RESOURCE_ROOT + "/flox-nri-overlay-hook.sh",
          "flox-nri-overlay-hook.sh");
      addInstallerAsset(
          "flox-nri-chown-hook.sh",
          FLOX_RESOURCE_ROOT + "/flox-nri-chown-hook.sh",
          "flox-nri-chown-hook.sh");
      addInstallerAsset(
          "runtime-flake.nix", FLOX_RESOURCE_ROOT + "/flake.nix", "build-assets/flake.nix");
    }

    private void addDefaultDebugToolsInstallerAssets() {
      addInstallerAsset(
          "debug-tools-rke2lab-debug-tooling.sh",
          FLOX_RESOURCE_ROOT + "/debug-tools/.sh.d/rke2lab-debug-tooling.sh",
          "build-assets/debug-tools/.sh.d/rke2lab-debug-tooling.sh");
      addInstallerAsset(
          "debug-tools-attach-live-flox-runtime-strace.sh",
          FLOX_RESOURCE_ROOT + "/debug-tools/attach_live_flox_runtime_strace.sh",
          "build-assets/debug-tools/attach_live_flox_runtime_strace.sh");
      addInstallerAsset(
          "debug-tools-crictl-kdns-repro.sh",
          FLOX_RESOURCE_ROOT + "/debug-tools/crictl-kdns-repro.sh",
          "build-assets/debug-tools/crictl-kdns-repro.sh");
      addInstallerAsset(
          "debug-tools-kdns-containerd-bundle-watch.sh",
          FLOX_RESOURCE_ROOT + "/debug-tools/kdns-containerd-bundle-watch.sh",
          "build-assets/debug-tools/kdns-containerd-bundle-watch.sh");
      addInstallerAsset(
          "debug-tools-kdns-containerd-remote-capture.sh",
          FLOX_RESOURCE_ROOT + "/debug-tools/kdns-containerd-remote-capture.sh",
          "build-assets/debug-tools/kdns-containerd-remote-capture.sh");
      addInstallerAsset(
          "debug-tools-master-runtime-pprof.sh",
          FLOX_RESOURCE_ROOT + "/debug-tools/master-runtime-pprof.sh",
          "build-assets/debug-tools/master-runtime-pprof.sh");
      addInstallerAsset(
          "debug-tools-rke2lab-dlv.sh",
          FLOX_RESOURCE_ROOT + "/debug-tools/rke2lab-dlv.sh",
          "build-assets/debug-tools/rke2lab-dlv.sh");
      addInstallerAsset(
          "debug-tools-rke2lab-runtime-dlv.sh",
          FLOX_RESOURCE_ROOT + "/debug-tools/rke2lab-runtime-dlv.sh",
          "build-assets/debug-tools/rke2lab-runtime-dlv.sh");
    }

    public Builder runtimeDaemonsetScriptPolicyAssets(
        RuntimeDaemonsetScriptPolicyAssets runtimeDaemonsetScriptPolicyAssets) {
      this.runtimeDaemonsetScriptPolicyAssets =
          Objects.requireNonNull(
              runtimeDaemonsetScriptPolicyAssets, "runtimeDaemonsetScriptPolicyAssets");
      return this;
    }

    private void addInstallerAsset(
        String configMapKey, String classpathResource, String mountPath) {
      addInstallerAsset(
          InstallerAsset.builder()
              .configMapKey(configMapKey)
              .classpathResource(classpathResource)
              .mountPath(mountPath)
              .build());
    }

    private void addInstallerAsset(InstallerAsset installerAsset) {
      this.floxInstallerConfigMapAssets.add(
          Objects.requireNonNull(installerAsset, "installerAsset"));
    }

    public FloxRuntimeAssets build() {
      return new FloxRuntimeAssets(this);
    }
  }

  /**
   * Describes one entry in the runtime-installer ConfigMap. Either {@link #classpathResource()} or
   * {@link #inlineContent()} must be set; if both are present, inline content wins so generated
   * boilerplate (env.json etc.) doesn't need a placeholder file in the resource tree.
   */
  public static final class InstallerAsset {
    private final String configMapKey;
    private final String classpathResource;
    private final String inlineContent;
    private final String mountPath;

    private InstallerAsset(Builder builder) {
      this.configMapKey = Objects.requireNonNull(builder.configMapKey, "configMapKey");
      this.classpathResource = builder.classpathResource;
      this.inlineContent = builder.inlineContent;
      this.mountPath = Objects.requireNonNull(builder.mountPath, "mountPath");
      if (classpathResource == null && inlineContent == null) {
        throw new IllegalArgumentException(
            "InstallerAsset '" + configMapKey + "' must set classpathResource or inlineContent");
      }
    }

    public static Builder builder() {
      return new Builder();
    }

    public String configMapKey() {
      return configMapKey;
    }

    public String classpathResource() {
      return classpathResource;
    }

    public String inlineContent() {
      return inlineContent;
    }

    public String mountPath() {
      return mountPath;
    }

    public static final class Builder {
      private String configMapKey;
      private String classpathResource;
      private String inlineContent;
      private String mountPath;

      private Builder() {}

      public Builder configMapKey(String configMapKey) {
        this.configMapKey = configMapKey;
        return this;
      }

      public Builder classpathResource(String classpathResource) {
        this.classpathResource = classpathResource;
        return this;
      }

      public Builder inlineContent(String inlineContent) {
        this.inlineContent = inlineContent;
        return this;
      }

      public Builder mountPath(String mountPath) {
        this.mountPath = mountPath;
        return this;
      }

      public InstallerAsset build() {
        return new InstallerAsset(this);
      }
    }
  }
}
