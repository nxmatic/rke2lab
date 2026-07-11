package io.nxmatic.rke2lab.incus.edge;

import io.nxmatic.rke2lab.incus.contract.ImageBuildException;
import io.nxmatic.rke2lab.incus.contract.ImageBuildRequest;
import io.nxmatic.rke2lab.incus.contract.ImageBuilder;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.osgi.service.component.annotations.Component;

/**
 * The realised incus image-build edge: produces the seed image's distrobuilder artifacts by
 * shelling {@code distrobuilder build-incus} when the binary is on {@code PATH}, otherwise by
 * streaming the bundled remote build recipe over {@code ssh} to the builder host. The single door
 * toward this one external contact — the {@code ProcessBuilder} mechanism formerly inlined in the
 * host {@code PulumiIncusImageProvider}. {@code ProcessBuilder} is playable, so this edge lives in
 * the OSGi world; SCR publishes it and the host provider composes it from the registry when a
 * rebuild is needed.
 *
 * <p><b>Runtime dependency:</b> {@code distrobuilder} (+ {@code sudo}) locally, or {@code ssh} and
 * key-based access to the builder host.
 */
@Component(service = ImageBuilder.class)
public final class DistrobuilderImageBuilder implements ImageBuilder {

  private static final String REMOTE_BUILD_SCRIPT_RESOURCE =
      "io/nxmatic/rke2lab/incus/edge/remote-build-incus-image.sh";

  @Override
  public void build(ImageBuildRequest request) {
    final Path workspace = Path.of(request.workspaceDir());
    final String localExecutable = tryResolveExecutable(request.builderBinary());

    if (!localExecutable.isBlank()) {
      final List<String> buildCommand =
          buildIncusAsRootCommand(
              localExecutable, request.localConfigPath(), request.localArtifactDir());
      runCommandOrThrow(
          workspace, buildCommand, "Failed to build Incus image artifacts using distrobuilder");
      return;
    }

    runRemoteBuildOrThrow(request);
  }

  @Override
  public String recipeDigest() {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(loadRecipeScript().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException ex) {
      throw new ImageBuildException("SHA-256 is not available", ex);
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

    runRemoteScriptOverSshOrThrow(
        Path.of(request.workspaceDir()),
        remoteHost,
        List.of(
            request.remoteWorkspaceDir(),
            request.remoteConfigPath(),
            request.remoteArtifactDir(),
            binary),
        "Failed to build Incus image artifacts on remote builder host " + remoteHost);
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

  private void runRemoteScriptOverSshOrThrow(
      Path workingDirectory, String remoteHost, List<String> scriptArgs, String failureMessage) {
    final String script = loadRecipeScript();

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

  private String loadRecipeScript() {
    try (var in = getClass().getClassLoader().getResourceAsStream(REMOTE_BUILD_SCRIPT_RESOURCE)) {
      if (in == null) {
        throw new ImageBuildException("Recipe script not found: " + REMOTE_BUILD_SCRIPT_RESOURCE);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new ImageBuildException(
          "Failed to load recipe script: " + REMOTE_BUILD_SCRIPT_RESOURCE, ex);
    }
  }
}
