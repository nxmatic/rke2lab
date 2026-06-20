// @codebase
package io.nxmatic.rke2lab.manifests.units.runtime.flox;

import io.nxmatic.rke2lab.manifests.units.runtime.daemonset.RuntimeDaemonsetScriptPolicyAssets;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

public final class FloxRuntimeAssets {

  private static final String FLOX_RESOURCE_ROOT = "/runtime/flox";
  private static final String ENV_RESOURCE_ROOT = FLOX_RESOURCE_ROOT + "/environment.d";
  private static final String NRI_PLUGIN_RESOURCE_ROOT = FLOX_RESOURCE_ROOT + "/nri-plugin";

  /**
   * The only per-env real file is {@code manifest.toml}; workload packages are produced by the
   * parent runtime flake (see {@code runtime/flox/flake.nix}) and referenced from each env's {@code
   * manifest.toml} via an absolute {@code flake = "path:/srv/host/.../runtime/flox#<output>"}.
   * {@code env.json} and {@code .gitattributes} are synthesized in code (uniform across envs).
   * Locks (flake.lock, manifest.lock) are owned by the parent runtime flake — the node regenerates
   * each env's manifest.lock at activation time.
   */
  private static final String MANIFEST_TOML_RESOURCE = "manifest.toml";

  private final Class<?> resourceAnchor;
  private final List<InstallerAsset> floxInstallerConfigMapAssets;
  private final RuntimeDaemonsetScriptPolicyAssets runtimeDaemonsetScriptPolicyAssets;

  private FloxRuntimeAssets(Builder builder) {
    this.resourceAnchor = builder.resourceAnchor;
    this.floxInstallerConfigMapAssets = List.copyOf(builder.floxInstallerConfigMapAssets);
    this.runtimeDaemonsetScriptPolicyAssets = builder.runtimeDaemonsetScriptPolicyAssets;
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Writes every installer asset directly to {@code targetDir}, laid out under the same relative
   * paths the Kubernetes ConfigMap-volume mount used to expose. The DaemonSet now mounts {@code
   * targetDir} as a hostPath instead of a ConfigMap because the aggregate asset payload is well
   * over Kubernetes' per-object 1 MiB limit.
   *
   * <p>Layout produced (rooted at {@code targetDir}):
   *
   * <ul>
   *   <li>{@code bin/flox-nri-plugin-installer.sh}, {@code bin/flox-nri-plugin-run.sh}
   *   <li>{@code bin/flox-nri-overlay-hook.sh}, {@code bin/flox-nri-env-link-hook.sh}, {@code
   *       bin/flox-nri-chown-hook.sh}
   *   <li>{@code build-assets/flake.nix}
   *   <li>{@code
   *       build-assets/environment.d/<category>/<env>/.flox/{env.json,env/flake.nix,env/manifest.toml,env/flake.lock,env/manifest.lock}}
   *   <li>{@code build-assets/debug-tools/...}
   *   <li>{@code build-assets/nri-plugin.tar.b64}, {@code build-assets/nri-plugin.manifest.json}
   *   <li>{@code .sh.d/<runtime-daemonset-script-policy entries>} — same layout the in-pod
   *       materializer writes, so host-mode and pod-mode resolve to one path.
   * </ul>
   */
  public void writeInstallerAssetTree(Path targetDir) throws IOException {
    Files.createDirectories(targetDir);

    for (InstallerAsset asset : floxInstallerConfigMapAssets) {
      writeText(targetDir.resolve(asset.mountPath()), resolveAssetContent(asset));
    }
    for (Map.Entry<String, String> entry :
        runtimeDaemonsetScriptPolicyAssets.relativePathsByKey().entrySet()) {
      final String content = runtimeDaemonsetScriptPolicyAssets.configMapData().get(entry.getKey());
      writeText(targetDir.resolve(entry.getValue()), content == null ? "" : content);
    }
    // The parent flake's `flox-nri-plugin` derivation has `src = ./nri-plugin`,
    // so the source tree must sit next to flake.nix on disk. Walk the
    // classpath nri-plugin/ resource tree and copy it into <targetDir>/nri-plugin/.
    copyClasspathTreeTo(NRI_PLUGIN_RESOURCE_ROOT, targetDir.resolve("nri-plugin"));
  }

  private static void writeText(Path target, String content) throws IOException {
    final String normalized = normalizeConfigMapText(content);
    Files.write(ensureParent(target), normalized.getBytes(StandardCharsets.UTF_8));
    applyExecutableBitIfNeeded(target);
  }

  /**
   * Mark shell scripts executable. The host-mode trampoline exec's {@code
   * bin/flox-nri-plugin-installer.sh} directly (no chmod step in between), so files we drop to disk
   * need their exec bit set at write time. We treat any {@code .sh} extension and any file under a
   * {@code bin/} directory as executable — matches the layout the legacy installer assumed.
   */
  private static void applyExecutableBitIfNeeded(Path target) throws IOException {
    final String name = target.getFileName().toString();
    final boolean nameLooksExecutable = name.endsWith(".sh");
    final boolean underBinDir =
        target.getParent() != null && "bin".equals(target.getParent().getFileName().toString());
    if (!nameLooksExecutable && !underBinDir) {
      return;
    }
    try {
      Files.setPosixFilePermissions(
          target, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
    } catch (UnsupportedOperationException ignored) {
      // Non-POSIX filesystem (shouldn't happen on the host paths we target).
    }
  }

  private static Path ensureParent(Path target) throws IOException {
    final Path parent = target.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    return target;
  }

  /**
   * Recursively copy every entry under {@code classpathRoot} out of the running bundle into {@code
   * targetDir}, preserving relative paths, via {@link Bundle#findEntries} (recurse). Bundle entry
   * paths are absolute ({@code /runtime/flox/...}); the relative path is taken against {@code
   * classpathRoot}. The exec bit on shell scripts is set via {@link #applyExecutableBitIfNeeded};
   * everything else lands at {@code 0644}. {@code manifests-core} only ever runs as an installed
   * bundle (its {@code @Component} is activated by SCR), so there is no flat-classpath branch.
   */
  private void copyClasspathTreeTo(String classpathRoot, Path targetDir) throws IOException {
    Files.createDirectories(targetDir);
    final Bundle bundle = FrameworkUtil.getBundle(FloxRuntimeAssets.class);
    final String root = classpathRoot.endsWith("/") ? classpathRoot : classpathRoot + "/";
    final Enumeration<URL> entries = bundle.findEntries(classpathRoot, "*", true);
    if (entries == null) {
      throw new IllegalStateException("Bundle resource root not found: " + classpathRoot);
    }
    while (entries.hasMoreElements()) {
      final URL entry = entries.nextElement();
      final String path = entry.getPath();
      if (path.endsWith("/")) {
        continue; // directory entry
      }
      final int rootIdx = path.indexOf(root);
      final String rel = rootIdx >= 0 ? path.substring(rootIdx + root.length()) : path;
      final Path dst = targetDir.resolve(rel);
      Files.createDirectories(dst.getParent());
      try (InputStream in = entry.openStream()) {
        Files.copy(in, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
      applyExecutableBitIfNeeded(dst);
    }
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
   * derived from the {@code environment.d/<category>/<name>/} directory layout on the classpath.
   */
  public record DiscoveredEnvironment(String category, String name) {
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
   * Returns discovered flox environments (category/name pairs) from the classpath.
   *
   * <p>Exposed for slot manifest generation during bootstrap.
   *
   * @return list of discovered environments
   */
  public List<DiscoveredEnvironment> getDiscoveredEnvironments() {
    return discoverEnvironments(resourceAnchor).stream()
        .sorted(
            (a, b) -> {
              int cmp = a.category().compareTo(b.category());
              return cmp != 0 ? cmp : a.name().compareTo(b.name());
            })
        .toList();
  }

  /**
   * Discover {@code category/name} pairs from the running bundle: every {@code manifest.toml} at
   * exactly {@code environment.d/<category>/<name>/manifest.toml}, via {@link Bundle#findEntries}.
   * {@code manifests-core} only ever runs as an installed bundle (its {@code @Component} is
   * activated by SCR; tests boot it under Felix too), so there is no flat-classpath branch. Bundle
   * entry paths are absolute; the {@code category/name} is parsed against {@code
   * ENV_RESOURCE_ROOT}.
   */
  private static Set<DiscoveredEnvironment> discoverEnvironments(Class<?> resourceAnchor) {
    final TreeSet<DiscoveredEnvironment> discovered =
        new TreeSet<>(
            (a, b) -> {
              int cmp = a.category().compareTo(b.category());
              return cmp != 0 ? cmp : a.name().compareTo(b.name());
            });

    final Bundle bundle = FrameworkUtil.getBundle(resourceAnchor);
    final String root = ENV_RESOURCE_ROOT.substring(1) + "/"; // "runtime/flox/environment.d/"
    final Enumeration<URL> entries =
        bundle.findEntries(ENV_RESOURCE_ROOT, MANIFEST_TOML_RESOURCE, true);
    if (entries == null) {
      return discovered;
    }
    while (entries.hasMoreElements()) {
      final String path = entries.nextElement().getPath();
      final int rootIdx = path.indexOf(root);
      if (rootIdx < 0) {
        continue;
      }
      final String tail =
          path.substring(rootIdx + root.length()); // <category>/<name>/manifest.toml
      final int firstSlash = tail.indexOf('/');
      final int secondSlash = firstSlash < 0 ? -1 : tail.indexOf('/', firstSlash + 1);
      if (secondSlash < 0) {
        continue;
      }
      discovered.add(
          new DiscoveredEnvironment(
              tail.substring(0, firstSlash), tail.substring(firstSlash + 1, secondSlash)));
    }
    return discovered;
  }

  public static final class Builder {
    private final Class<?> resourceAnchor = FloxRuntimeAssets.class;
    private final List<InstallerAsset> floxInstallerConfigMapAssets = new ArrayList<>();
    private RuntimeDaemonsetScriptPolicyAssets runtimeDaemonsetScriptPolicyAssets =
        RuntimeDaemonsetScriptPolicyAssets.builder().build();

    private Builder() {
      addDiscoveredEnvironmentAssets();
      addDefaultCoreInstallerAssets();
      addDefaultDebugToolsInstallerAssets();
    }

    /**
     * Discover every {@code environment.d/<category>/<name>/} directory on the classpath that has
     * both {@code flake.nix} and {@code manifest.toml}, then register the assets the host installer
     * needs.
     */
    private void addDiscoveredEnvironmentAssets() {
      for (DiscoveredEnvironment env : discoverEnvironments(resourceAnchor)) {
        addFloxEnvironmentAssets(env);
      }
    }

    /**
     * Register installer ConfigMap entries for one Flox environment. {@code manifest.toml} is the
     * only per-env real file — workload packages live in the parent runtime flake and are
     * referenced from manifest.toml by absolute path. {@code env.json} is synthesized in code from
     * the env's identity. {@code manifest.lock} is cluster state, not a build artifact: the master
     * writes it at first activation, peers read what's on the shared host filesystem.
     */
    private void addFloxEnvironmentAssets(DiscoveredEnvironment env) {
      final String envPrefix = env.envPrefix();
      final String resourcePrefix = ENV_RESOURCE_ROOT + "/" + envPrefix;
      final String configMapPrefix = env.configMapPrefix();
      // The runtime-installer's `flox activate --dir <env-dir>` resolves the
      // env at <FLOX_RUNTIME_ROOT>/environment.d/<category>/<name>/, so write the env
      // tree at the asset root — not under build-assets/.
      final String mountPrefix = "environment.d/" + envPrefix;

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
    }

    private void addDefaultCoreInstallerAssets() {
      addInstallerAsset(
          "flox-runtime-lib.sh",
          FLOX_RESOURCE_ROOT + "/bin/flox-runtime-lib.sh",
          "bin/flox-runtime-lib.sh");
      addInstallerAsset(
          "flox-nri-plugin-installer.sh",
          FLOX_RESOURCE_ROOT + "/bin/flox-nri-plugin-installer.sh",
          "bin/flox-nri-plugin-installer.sh");
      addInstallerAsset(
          "flox-nri-plugin-run.sh",
          FLOX_RESOURCE_ROOT + "/bin/flox-nri-plugin-run.sh",
          "bin/flox-nri-plugin-run.sh");
      addInstallerAsset(
          "flox-nri-plugin-reload.sh",
          FLOX_RESOURCE_ROOT + "/bin/flox-nri-plugin-reload.sh",
          "bin/flox-nri-plugin-reload.sh");
      addInstallerAsset(
          "flox-nri-overlay-hook.sh",
          FLOX_RESOURCE_ROOT + "/bin/flox-nri-overlay-hook.sh",
          "bin/flox-nri-overlay-hook.sh");
      addInstallerAsset(
          "flox-nri-env-link-hook.sh",
          FLOX_RESOURCE_ROOT + "/bin/flox-nri-env-link-hook.sh",
          "bin/flox-nri-env-link-hook.sh");
      addInstallerAsset(
          "flox-nri-chown-hook.sh",
          FLOX_RESOURCE_ROOT + "/bin/flox-nri-chown-hook.sh",
          "bin/flox-nri-chown-hook.sh");
      addInstallerAsset("runtime-flake.nix", FLOX_RESOURCE_ROOT + "/flake.nix", "flake.nix");
      // Parent flake.lock and per-env manifest.lock are NOT shipped from the
      // repo. Locks are cluster state (the master resolves them at first
      // activation; peers read what the master wrote on the shared host
      // filesystem). Pinning them at build time would conflate build artifact
      // with cluster state and force a repo update on every input bump.
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
