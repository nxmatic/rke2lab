package io.nxmatic.rke2lab.incus.edge;

import io.nxmatic.rke2lab.incus.contract.ImageBuildRequest;
import io.nxmatic.rke2lab.incus.contract.ImageBuilder;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.osgi.service.component.annotations.Component;

/**
 * The realised incus image-build edge: produces the seed image's distrobuilder artifacts by
 * shelling {@code distrobuilder build-incus} when the binary is on {@code PATH}, otherwise by
 * streaming the bundled build recipe over {@code ssh} to the builder host. The single door toward
 * this one external contact — the {@code ProcessBuilder} mechanism formerly inlined in the host
 * {@code PulumiIncusImageProvider}. {@code ProcessBuilder} is playable, so this edge lives in the
 * OSGi world; SCR publishes it and the host provider composes it from the registry when a rebuild
 * is needed.
 *
 * <p>The edge is the SOLE owner of the build recipe: BOTH the {@code remote-build-incus-image.sh}
 * driver AND the {@code incus-distrobuilder.yaml} distrobuilder config are its bundle resources. A
 * local build materialises the config to a temp file; a remote build delivers both resources over
 * the SAME ssh/stdin channel (base64 heredocs — no NFS hand-off, no quoting hazard). {@link
 * #recipeDigest()} folds both resources' bytes, so a change to EITHER invalidates the host's image
 * cache — the host never holds the recipe, only its digest.
 *
 * <p><b>Runtime dependency:</b> {@code distrobuilder} (+ {@code sudo}) locally, or {@code ssh} and
 * key-based access to the builder host.
 */
@Component(service = ImageBuilder.class)
public final class DistrobuilderImageBuilder implements ImageBuilder {

  private static final String REMOTE_BUILD_SCRIPT_RESOURCE =
      "io/nxmatic/rke2lab/incus/edge/remote-build-incus-image.sh";
  private static final String DISTROBUILDER_CONFIG_RESOURCE =
      "io/nxmatic/rke2lab/incus/edge/incus-distrobuilder.yaml";
  private static final String CONFIG_FILENAME = "incus-distrobuilder.yaml";

  @Override
  public Optional<String> build(ImageBuildRequest request) {
    // The internal plumbing throws the edge-private ImageBuildException deep in the process code;
    // this boundary converts it once into the seam's human summary (empty = success).
    try {
      buildOrThrow(request);
      return Optional.empty();
    } catch (ImageBuildException failed) {
      return Optional.of(failed.summary());
    }
  }

  private void buildOrThrow(ImageBuildRequest request) {
    final Path workspace = Path.of(request.workspaceDir());
    final String localExecutable = tryResolveExecutable(request.builderBinary());

    if (!localExecutable.isBlank()) {
      runLocalBuildOrThrow(workspace, localExecutable, request.localArtifactDir());
      return;
    }

    runRemoteBuildOrThrow(request);
  }

  @Override
  public String recipeDigest() {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(loadResource(REMOTE_BUILD_SCRIPT_RESOURCE));
      digest.update((byte) '\n');
      digest.update(loadResource(DISTROBUILDER_CONFIG_RESOURCE));
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException ex) {
      throw new ImageBuildException("SHA-256 is not available", ex);
    }
  }

  private void runLocalBuildOrThrow(Path workspace, String executable, String artifactDir) {
    final Path configFile = materializeConfig();
    try {
      final List<String> buildCommand =
          buildIncusAsRootCommand(executable, configFile.toString(), artifactDir);
      runCommandOrThrow(
          workspace, buildCommand, "Failed to build Incus image artifacts using distrobuilder");
    } finally {
      try {
        Files.deleteIfExists(configFile);
      } catch (IOException ignored) {
        // A leftover temp config is harmless; the build outcome already stands.
      }
    }
  }

  private void runRemoteBuildOrThrow(ImageBuildRequest request) {
    final String remoteHost = request.remoteHost();
    if (remoteHost.isBlank()) {
      throw new ImageBuildException(
          "Unable to locate local executable '"
              + request.builderBinary()
              + "' and no remote image.builderHost is configured");
    }

    final String binary =
        request.builderBinary().isBlank() ? "distrobuilder" : request.builderBinary();

    runRemoteBootstrapOverSshOrThrow(
        Path.of(request.workspaceDir()),
        remoteHost,
        List.of(request.remoteWorkspaceDir(), request.remoteArtifactDir(), binary),
        "Failed to build Incus image artifacts on remote builder host " + remoteHost);
  }

  /** Write the bundled distrobuilder config to a temp file for a local {@code build-incus}. */
  private Path materializeConfig() {
    try {
      final Path configFile = Files.createTempFile("incus-distrobuilder-", ".yaml");
      Files.write(configFile, loadResource(DISTROBUILDER_CONFIG_RESOURCE));
      return configFile;
    } catch (IOException ex) {
      throw new ImageBuildException(
          "Failed to materialise distrobuilder config: " + ex.getMessage(), ex);
    }
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
      throw new ImageBuildException(
          "distrobuilder build requires root privileges, but sudo was not found");
    }

    return List.of(
        sudoExecutable, "-n", distrobuilderExecutable, "build-incus", configPath, artifactDir);
  }

  private boolean isRunningAsRoot() {
    return "root".equals(System.getProperty("user.name", ""));
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
        throw new ImageBuildException(
            failureMessage
                + " (exit="
                + exitCode
                + ")\nCommand: "
                + String.join(" ", command)
                + "\nOutput:\n"
                + output);
      }
    } catch (IOException ex) {
      throw new ImageBuildException(failureMessage + ": " + ex.getMessage(), ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new ImageBuildException(failureMessage + ": " + ex.getMessage(), ex);
    }
  }

  private void runRemoteBootstrapOverSshOrThrow(
      Path workingDirectory, String remoteHost, List<String> scriptArgs, String failureMessage) {
    final String bootstrap = remoteBootstrap();

    final List<String> command = new ArrayList<>();
    command.add("ssh");
    command.add(remoteHost);
    command.add("sh");
    command.add("-s");
    command.addAll(scriptArgs);

    final ProcessBuilder pb = new ProcessBuilder(command);
    pb.directory(workingDirectory.toFile());
    pb.redirectErrorStream(true);

    try {
      final Process process = pb.start();
      try (OutputStream stdin = process.getOutputStream()) {
        stdin.write(bootstrap.getBytes(StandardCharsets.UTF_8));
        stdin.flush();
      }

      final String output =
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      final int exitCode = process.waitFor();

      if (exitCode != 0) {
        throw new ImageBuildException(
            failureMessage
                + " (exit="
                + exitCode
                + ")\nCommand: "
                + String.join(" ", command)
                + "\nOutput:\n"
                + output);
      }
    } catch (IOException ex) {
      throw new ImageBuildException(failureMessage + ": " + ex.getMessage(), ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new ImageBuildException(failureMessage + ": " + ex.getMessage(), ex);
    }
  }

  /**
   * The self-contained bootstrap streamed to the builder over {@code ssh … sh -s}. It decodes the
   * two bundle resources (the driver script + the distrobuilder config) from base64 heredocs into a
   * throwaway temp dir, then runs the driver with the config it just wrote. base64 keeps the
   * heredoc delimiters collision-free (the payload can be any text/binary) and needs nothing beyond
   * coreutils. Positional args from {@code sh -s}: {@code $1}=remote workspace, {@code $2}=artifact
   * dir, {@code $3}=builder binary; the config path is the temp file this bootstrap writes.
   */
  private String remoteBootstrap() {
    final String scriptB64 =
        Base64.getEncoder().encodeToString(loadResource(REMOTE_BUILD_SCRIPT_RESOURCE));
    final String configB64 =
        Base64.getEncoder().encodeToString(loadResource(DISTROBUILDER_CONFIG_RESOURCE));
    return String.join(
        "\n",
        "set -eu",
        "export PATH=\"/run/current-system/sw/bin:/nix/var/nix/profiles/default/bin:/usr/local/bin:/usr/bin:/bin:$PATH\"",
        "tmp_dir=$(mktemp -d)",
        "trap 'rm -rf \"$tmp_dir\"' EXIT",
        "base64 -d > \"$tmp_dir/build.sh\" <<'B64SCRIPT'",
        scriptB64,
        "B64SCRIPT",
        "base64 -d > \"$tmp_dir/" + CONFIG_FILENAME + "\" <<'B64CONFIG'",
        configB64,
        "B64CONFIG",
        "chmod 700 \"$tmp_dir/build.sh\"",
        "\"$tmp_dir/build.sh\" \"$1\" \"$tmp_dir/" + CONFIG_FILENAME + "\" \"$2\" \"$3\"");
  }

  private byte[] loadResource(String resource) {
    try (var in = getClass().getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new ImageBuildException("Bundle resource not found: " + resource);
      }
      return in.readAllBytes();
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed to load bundle resource: " + resource, ex);
    }
  }
}
