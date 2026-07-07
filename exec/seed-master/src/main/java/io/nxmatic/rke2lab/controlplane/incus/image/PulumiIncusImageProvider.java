package io.nxmatic.rke2lab.controlplane.incus.image;

import com.pulumi.core.Output;
import com.pulumi.deployment.Deployment;
import com.pulumi.deployment.InvokeOptions;
import com.pulumi.incus.Image;
import com.pulumi.incus.ImageArgs;
import com.pulumi.incus.IncusFunctions;
import com.pulumi.incus.Provider;
import com.pulumi.incus.inputs.GetImagePlainArgs;
import com.pulumi.incus.inputs.ImageAliasArgs;
import com.pulumi.incus.inputs.ImageSourceFileArgs;
import com.pulumi.resources.CustomResourceOptions;
import com.pulumi.resources.Resource;
import io.nxmatic.rke2lab.config.port.BootstrapConfig;
import io.nxmatic.rke2lab.config.port.BootstrapConfig.WorktreeHost;
import io.nxmatic.rke2lab.controlplane.SeedLog;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Stage A seed image orchestration for Incus image lifecycle.
 *
 * <p>This class is intentionally not a Pulumi dynamic resource provider. It coordinates
 * local/remote artifact materialization and then creates/reads a standard Incus provider-managed
 * {@link Image}.
 */
public final class PulumiIncusImageProvider {

  private static final URI REMOTE_BUILD_SCRIPT_RESOURCE =
      URI.create(
          "classpath:/META-INF/io.nxmatic/rke2lab/controlplane/incus/image/remote-build-incus-image.sh");

  private static final String INCUS_METADATA_FILENAME = "incus.tar.xz";

  private static final String INCUS_ROOTFS_FILENAME = "rootfs.squashfs";

  private final BootstrapConfig config;

  private String lastBuildChecksum = "";

  public PulumiIncusImageProvider(BootstrapConfig config) {
    this.config = config;
  }

  /** Ensure the seed image exists and return its fingerprint output. */
  public Output<String> ensureSeedImageFingerprint(
      InvokeOptions invokeOptions, Provider provider, Optional<Resource> projectDependency) {
    final long startedAt = System.nanoTime();
    logInfo("ensureSeedImageFingerprint: starting");
    final Path workspace = config.worktreeDirOn(WorktreeHost.DARWIN);
    final Path distrobuilderConfigPath = materializeDistrobuilderConfig(workspace);
    this.lastBuildChecksum = computeBuildChecksum(distrobuilderConfigPath);
    logInfo("ensureSeedImageFingerprint: computed build checksum after " + elapsedSince(startedAt));

    final long existingLookupStartedAt = System.nanoTime();
    final String existingFingerprint = resolveExistingImageFingerprint(invokeOptions);
    if (!existingFingerprint.isBlank()) {
      logInfo(
          "ensureSeedImageFingerprint: reusing existing image fingerprint after "
              + elapsedSince(existingLookupStartedAt)
              + " (fingerprint="
              + existingFingerprint
              + ")");
      return Output.of(existingFingerprint);
    }
    logInfo(
        "ensureSeedImageFingerprint: existing image lookup completed after "
            + elapsedSince(existingLookupStartedAt)
            + " (no reusable alias found)");

    final boolean preview = isDryRun();
    if (preview) {
      final long previewArtifactsStartedAt = System.nanoTime();
      final Path artifactDir = resolveReadableLocalArtifactDir(resolveArtifactDir(workspace));
      logInfo(
          "ensureSeedImageFingerprint: preview artifact directory resolved after "
              + elapsedSince(previewArtifactsStartedAt)
              + " (artifactDir="
              + artifactDir
              + ")");
      final BuiltImageArtifacts previewArtifacts =
          new BuiltImageArtifacts(
              artifactDir.resolve(INCUS_METADATA_FILENAME),
              artifactDir.resolve(INCUS_ROOTFS_FILENAME));
      logInfo("ensureSeedImageFingerprint: preview mode scheduling image resource from artifacts");
      return createSeedImageFromArtifacts(provider, previewArtifacts, projectDependency)
          .fingerprint();
    }

    final long localArtifactsStartedAt = System.nanoTime();
    final BuiltImageArtifacts artifacts = ensureLocalImageArtifacts();
    logInfo(
        "ensureSeedImageFingerprint: local artifacts ensured after "
            + elapsedSince(localArtifactsStartedAt));
    return createSeedImageFromArtifacts(provider, artifacts, projectDependency).fingerprint();
  }

  public String buildChecksum() {
    return lastBuildChecksum;
  }

  private String resolveExistingImageFingerprint(InvokeOptions invokeOptions) {
    try {
      final var existing =
          IncusFunctions.getImagePlain(
                  GetImagePlainArgs.builder()
                      .name(config.imageAlias())
                      .project(config.incusProject())
                      .build(),
                  invokeOptions)
              .join();

      if (existing == null) {
        return "";
      }

      final String fingerprint = existing.fingerprint();
      return fingerprint == null ? "" : fingerprint.trim();
    } catch (Exception ignored) {
      return "";
    }
  }

  private boolean isDryRun() {
    return Deployment.getInstance().isDryRun();
  }

  private Image createSeedImageFromArtifacts(
      Provider provider, BuiltImageArtifacts artifacts, Optional<Resource> projectDependency) {
    final CustomResourceOptions.Builder optionsBuilder =
        CustomResourceOptions.builder().provider(provider);
    projectDependency.ifPresent(dep -> optionsBuilder.dependsOn(List.of(dep)));

    return new Image(
        "seed-image",
        ImageArgs.builder()
            .project(config.incusProject())
            .aliases(ImageAliasArgs.builder().name(config.imageAlias()).build())
            .sourceFile(
                ImageSourceFileArgs.builder()
                    .metadataPath(artifacts.metadataPath().toString())
                    .dataPath(artifacts.dataPath().toString())
                    .build())
            .build(),
        optionsBuilder.build());
  }

  private BuiltImageArtifacts ensureLocalImageArtifacts() {
    final long startedAt = System.nanoTime();
    logInfo("ensureLocalImageArtifacts: starting");
    final Path workspace = config.worktreeDirOn(WorktreeHost.DARWIN);
    final Path artifactDir = resolveArtifactDir(workspace);
    final Path distrobuilderConfigPath = materializeDistrobuilderConfig(workspace);
    final String expectedBuildChecksum = computeBuildChecksum(distrobuilderConfigPath);
    final Path checksumMarkerPath = artifactDir.resolve(".build-checksum.sha256");

    Path readableArtifactDir = resolveReadableLocalArtifactDir(artifactDir);
    Path metadataPath = readableArtifactDir.resolve(INCUS_METADATA_FILENAME);
    Path dataPath = readableArtifactDir.resolve(INCUS_ROOTFS_FILENAME);

    if (Files.exists(metadataPath)
        && Files.exists(dataPath)
        && checksumMarkerMatches(checksumMarkerPath, expectedBuildChecksum)) {
      logInfo(
          "ensureLocalImageArtifacts: reusing existing artifacts after " + elapsedSince(startedAt));
      return new BuiltImageArtifacts(metadataPath, dataPath);
    }

    try {
      Files.createDirectories(artifactDir);
    } catch (IOException ex) {
      throw new IllegalStateException(
          "Failed to create image artifact directory: " + artifactDir, ex);
    }

    final String distrobuilderExecutable = tryResolveExecutable(config.imageBuilderBinary());
    if (!distrobuilderExecutable.isBlank()) {
      final List<String> buildCommand =
          buildIncusAsRootCommand(
              distrobuilderExecutable, distrobuilderConfigPath.toString(), artifactDir.toString());
      runCommandOrThrow(
          workspace, buildCommand, "Failed to build Incus image artifacts using distrobuilder");
    } else {
      runRemoteBuildOrThrow(workspace, distrobuilderConfigPath, artifactDir);
    }

    writeChecksumMarker(checksumMarkerPath, expectedBuildChecksum);

    metadataPath = artifactDir.resolve(INCUS_METADATA_FILENAME);
    dataPath = artifactDir.resolve(INCUS_ROOTFS_FILENAME);

    if (!Files.exists(metadataPath) || !Files.exists(dataPath)) {
      throw new IllegalStateException(
          "Distrobuilder did not produce expected artifacts. Missing files in "
              + artifactDir
              + " (expected incus.tar.xz and rootfs.squashfs). "
              + "Configured artifact directory: "
              + artifactDir);
    }

    logInfo("ensureLocalImageArtifacts: complete after " + elapsedSince(startedAt));
    return new BuiltImageArtifacts(metadataPath, dataPath);
  }

  private static void logInfo(String message) {
    SeedLog.debug("seed-image", message);
  }

  private static String elapsedSince(long startedAtNanos) {
    return Duration.ofNanos(System.nanoTime() - startedAtNanos).toString();
  }

  private String computeBuildChecksum(Path distrobuilderConfigPath) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(Files.readAllBytes(distrobuilderConfigPath));

      digest.update((byte) '\n');
      digest.update(config.imageAlias().getBytes(StandardCharsets.UTF_8));

      digest.update((byte) '\n');
      final String builderBinary =
          config.imageBuilderBinary() == null ? "" : config.imageBuilderBinary();
      digest.update(builderBinary.getBytes(StandardCharsets.UTF_8));

      digest.update((byte) '\n');
      final String builderHost = config.imageBuilderHost() == null ? "" : config.imageBuilderHost();
      digest.update(builderHost.getBytes(StandardCharsets.UTF_8));

      digest.update((byte) '\n');
      digest.update(
          loadClasspathScript(REMOTE_BUILD_SCRIPT_RESOURCE).getBytes(StandardCharsets.UTF_8));

      return HexFormat.of().formatHex(digest.digest());
    } catch (IOException ex) {
      throw new IllegalStateException(
          "Failed to compute distrobuilder checksum from config: " + distrobuilderConfigPath, ex);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
  }

  private boolean checksumMarkerMatches(Path checksumMarkerPath, String expectedChecksum) {
    if (!Files.exists(checksumMarkerPath)) {
      return false;
    }

    try {
      final String current = Files.readString(checksumMarkerPath, StandardCharsets.UTF_8).trim();
      return expectedChecksum.equals(current);
    } catch (AccessDeniedException ex) {
      return false;
    } catch (IOException ex) {
      return false;
    }
  }

  private void writeChecksumMarker(Path checksumMarkerPath, String checksum) {
    try {
      final Path parent = checksumMarkerPath.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(
          checksumMarkerPath, checksum + System.lineSeparator(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new IllegalStateException(
          "Failed to write distrobuilder checksum marker: " + checksumMarkerPath, ex);
    }
  }

  private Path resolveArtifactDir(Path workspace) {
    final Path configured = config.imageSharedFolder();
    final Path sharedFolderPath =
        configured.isAbsolute()
            ? configured.normalize()
            : workspace.resolve(configured).normalize();
    return sharedFolderPath.resolve(config.imageAlias()).normalize();
  }

  private Path resolveSharedFolder(Path workspace) {
    final Path configured = config.imageSharedFolder();
    if (configured.isAbsolute()) {
      return configured.normalize();
    }
    return workspace.resolve(configured).normalize();
  }

  private Path resolveReadableLocalArtifactDir(Path artifactDir) {
    final Path normalized = artifactDir.toAbsolutePath().normalize();
    final List<Path> candidates = new ArrayList<>();
    candidates.add(normalized);
    candidates.add(siblingNfsDir(normalized));

    final String raw = normalized.toString();
    if (raw.startsWith("/net/")) {
      final int privateIndex = raw.indexOf("/private/");
      if (privateIndex >= 0) {
        final String privatePath = raw.substring(privateIndex);
        final Path privateCandidate = Path.of(privatePath).normalize();
        candidates.add(privateCandidate);
        candidates.add(siblingNfsDir(privateCandidate));

        final String withoutPrivatePrefix = privatePath.substring("/private".length());
        final Path directCandidate = Path.of(withoutPrivatePrefix).normalize();
        candidates.add(directCandidate);
        candidates.add(siblingNfsDir(directCandidate));
      }
    }

    for (Path candidate : candidates) {
      if (Files.exists(candidate.resolve(INCUS_METADATA_FILENAME))
          && Files.exists(candidate.resolve(INCUS_ROOTFS_FILENAME))) {
        return candidate;
      }
    }

    for (Path candidate : candidates) {
      if (Files.isDirectory(candidate)) {
        return candidate;
      }
    }

    return normalized;
  }

  private Path siblingNfsDir(Path path) {
    final Path fileName = path.getFileName();
    if (fileName == null) {
      return path;
    }
    return path.resolveSibling(fileName.toString() + ".nfs").normalize();
  }

  private Path materializeDistrobuilderConfig(Path workspace) {
    final String configuredPath = config.imageDistrobuilderConfig().toString();
    if (configuredPath.startsWith("classpath:/")) {
      final String resourcePath = configuredPath.substring("classpath:/".length());
      final Path destination =
          workspace.resolve(".local.d/tmp/" + Path.of(resourcePath).getFileName());
      try {
        Files.createDirectories(destination.getParent());
        try (var in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
          if (in == null) {
            throw new IllegalStateException("Classpath resource not found: " + resourcePath);
          }
          Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        return destination;
      } catch (IOException ex) {
        throw new IllegalStateException(
            "Failed to materialize distrobuilder config from classpath: " + configuredPath, ex);
      }
    }

    final Path configured = Path.of(configuredPath);
    final Path resolved = configured.isAbsolute() ? configured : workspace.resolve(configured);
    if (!Files.exists(resolved)) {
      throw new IllegalStateException("Distrobuilder config not found: " + resolved);
    }
    return resolved.normalize();
  }

  private String tryResolveExecutable(String configuredBinary) {
    if (configuredBinary == null || configuredBinary.isBlank()) {
      return "";
    }

    final String trimmedBinary = configuredBinary.trim();
    if (trimmedBinary.contains("/")) {
      final Path explicitPath = Path.of(trimmedBinary).toAbsolutePath().normalize();
      if (Files.isExecutable(explicitPath)) {
        return explicitPath.toString();
      }
      return "";
    }

    final Set<Path> searchDirectories = new LinkedHashSet<>();

    final String pathEnv = System.getenv("PATH");
    if (pathEnv != null && !pathEnv.isBlank()) {
      for (String entry : pathEnv.split(":")) {
        if (entry != null && !entry.isBlank()) {
          searchDirectories.add(Path.of(entry).toAbsolutePath().normalize());
        }
      }
    }

    final String javaHome = System.getProperty("java.home", "");
    if (!javaHome.isBlank()) {
      searchDirectories.add(Path.of(javaHome).resolve("bin").toAbsolutePath().normalize());
    }

    searchDirectories.add(Path.of("/run/current-system/sw/bin"));
    searchDirectories.add(Path.of("/nix/var/nix/profiles/default/bin"));
    searchDirectories.add(Path.of("/opt/homebrew/bin"));
    searchDirectories.add(Path.of("/usr/local/bin"));
    searchDirectories.add(Path.of("/usr/bin"));
    searchDirectories.add(Path.of("/bin"));

    final String home = System.getProperty("user.home", "");
    if (!home.isBlank()) {
      searchDirectories.add(Path.of(home).resolve(".flox/bin").toAbsolutePath().normalize());
    }

    for (Path directory : searchDirectories) {
      final Path candidate = directory.resolve(trimmedBinary).normalize();
      if (Files.isExecutable(candidate)) {
        return candidate.toString();
      }
    }

    return "";
  }

  private List<String> buildIncusAsRootCommand(
      String distrobuilderExecutable, String configPath, String artifactDir) {
    final List<String> buildCommand =
        List.of(distrobuilderExecutable, "build-incus", configPath, artifactDir);

    if (isRunningAsRoot()) {
      return buildCommand;
    }

    final String sudoExecutable = tryResolveExecutable("sudo");
    if (sudoExecutable.isBlank()) {
      throw new IllegalStateException(
          "distrobuilder build requires root privileges, but sudo was not found");
    }

    return List.of(
        sudoExecutable, "-n", distrobuilderExecutable, "build-incus", configPath, artifactDir);
  }

  private boolean isRunningAsRoot() {
    return "root".equals(System.getProperty("user.name", ""));
  }

  private void runRemoteBuildOrThrow(
      Path worktreeDir, Path distrobuilderConfigFile, Path artifactDir) {
    final String remoteHost = config.imageBuilderHost();
    if (remoteHost == null || remoteHost.isBlank()) {
      throw new IllegalStateException(
          "Unable to locate local executable '"
              + config.imageBuilderBinary()
              + "' and no remote "
              + "image.builderHost is configured");
    }

    final String binary =
        config.imageBuilderBinary() == null || config.imageBuilderBinary().isBlank()
            ? "distrobuilder"
            : config.imageBuilderBinary().trim();
    final String remoteWorktreeDir = toNixosPath(worktreeDir);
    final String remoteDistrobuilderConfigFile = toNixosPath(distrobuilderConfigFile);
    final String remoteArtifactDir =
        toNixosPath(resolveSharedFolder(worktreeDir).resolve(config.imageAlias()));

    runRemoteScriptOverSshOrThrow(
        worktreeDir,
        remoteHost,
        REMOTE_BUILD_SCRIPT_RESOURCE,
        List.of(remoteWorktreeDir, remoteDistrobuilderConfigFile, remoteArtifactDir, binary),
        "Failed to build Incus image artifacts on remote builder host " + remoteHost);
  }

  private String toNixosPath(Path path) {
    return config.pathOn(WorktreeHost.NIXOS, path).toString();
  }

  private void runRemoteScriptOverSshOrThrow(
      Path workingDirectory,
      String remoteHost,
      URI scriptResourcePath,
      List<String> scriptArgs,
      String failureMessage) {
    final String script = loadClasspathScript(scriptResourcePath);

    final List<String> command = new ArrayList<>();
    command.add("ssh");
    command.add(remoteHost);
    command.add("sh");
    command.add("-lc");
    command.add(
        "'set -eu; tmp_dir=$(mktemp -d); trap \"rm -rf \\\"$tmp_dir\\\"\" EXIT; "
            + "script_path=\"$tmp_dir/remote-build-incus-image.sh\"; "
            + "cat > \"$script_path\"; chmod 700 \"$script_path\"; "
            + "\"$script_path\" \"$@\"'");
    command.add("--");
    command.addAll(scriptArgs);

    final ProcessBuilder pb = new ProcessBuilder(command);
    pb.directory(workingDirectory.toFile());
    pb.redirectErrorStream(true);

    try {
      final Process process = pb.start();
      try (OutputStream stdin = process.getOutputStream()) {
        stdin.write(script.getBytes(StandardCharsets.UTF_8));
        stdin.flush();
      }

      final String output =
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      final int exitCode = process.waitFor();

      if (exitCode != 0) {
        throw new IllegalStateException(
            failureMessage
                + " (exit="
                + exitCode
                + ")\nCommand: "
                + String.join(" ", command)
                + "\nOutput:\n"
                + output);
      }
    } catch (IOException | InterruptedException ex) {
      if (ex instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new IllegalStateException(failureMessage + ": " + ex.getMessage(), ex);
    }
  }

  private String loadClasspathScript(URI resourcePath) {
    if (resourcePath == null || !"classpath".equals(resourcePath.getScheme())) {
      throw new IllegalStateException("Expected classpath URI, got: " + resourcePath);
    }

    String path = resourcePath.getPath();
    if (path == null || path.isBlank()) {
      throw new IllegalStateException("Classpath URI has empty path: " + resourcePath);
    }
    if (path.startsWith("/")) {
      path = path.substring(1);
    }

    try (var in = getClass().getClassLoader().getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("Classpath script not found: " + path);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to load classpath script: " + path, ex);
    }
  }

  private void runCommandOrThrow(
      Path workingDirectory, List<String> command, String failureMessage) {
    final ProcessBuilder pb = new ProcessBuilder(command);
    pb.directory(workingDirectory.toFile());
    pb.redirectErrorStream(true);

    try {
      final Process process = pb.start();
      final String output =
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      final int exitCode = process.waitFor();

      if (exitCode != 0) {
        throw new IllegalStateException(
            failureMessage
                + " (exit="
                + exitCode
                + ")\nCommand: "
                + String.join(" ", command)
                + "\nOutput:\n"
                + output);
      }
    } catch (IOException | InterruptedException ex) {
      if (ex instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new IllegalStateException(failureMessage + ": " + ex.getMessage(), ex);
    }
  }

  private record BuiltImageArtifacts(Path metadataPath, Path dataPath) {}
}
