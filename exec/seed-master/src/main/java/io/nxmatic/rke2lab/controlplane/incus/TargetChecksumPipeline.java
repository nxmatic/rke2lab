package io.nxmatic.rke2lab.controlplane.incus;

import io.nxmatic.rke2lab.controlplane.incus.IncusResourceBootstrap.BootstrapPaths;
import io.nxmatic.rke2lab.pipeline.FluentTopicRunner;
import io.nxmatic.rke2lab.pipeline.OnFailure;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Fluent pipeline for computing per-target provisioning checksums.
 *
 * <p>Usage:
 *
 * <pre>
 * Map&lt;String, String&gt; checksums = TargetChecksumPipeline.begin(paths, registry)
 *   .onFailure((topic, cause) -> logError("Checksum failed", cause))
 *   .during("cloud-init", cloudInit -> cloudInit.fromCloudInitRoots())
 *   .then()
 *   .during("registered components", components -> components.fromRegistry())
 *   .collectChecksums();
 * </pre>
 */
public final class TargetChecksumPipeline {

  private TargetChecksumPipeline() {}

  public static AwaitingOnFailure begin(BootstrapPaths paths, ProvisioningTargetRegistry registry) {
    return new AwaitingOnFailure(new State(paths, registry));
  }

  private static final class State {
    final BootstrapPaths paths;
    final ProvisioningTargetRegistry registry;
    final LinkedHashMap<String, String> targetChecksums = new LinkedHashMap<>();
    OnFailure onFailure = (topic, cause) -> {}; // no-op default
    final FluentTopicRunner runner = new FluentTopicRunner("target-checksum");

    State(BootstrapPaths paths, ProvisioningTargetRegistry registry) {
      this.paths = paths;
      this.registry = registry;
    }
  }

  public static final class AwaitingOnFailure {
    private final State state;

    AwaitingOnFailure(State state) {
      this.state = state;
    }

    public AwaitingCloudInit onFailure(OnFailure onFailure) {
      state.onFailure = onFailure;
      return new AwaitingCloudInit(state);
    }

    public CloudInitDone during(
        String topic, Function<CloudInitTargetStage, CloudInitTargetStage> body) {
      final CloudInitTargetStage stage = new CloudInitTargetStage(state);
      state.runner.runDuring(topic, stage, body, state.onFailure);
      return new CloudInitDone(state);
    }
  }

  public static final class AwaitingCloudInit {
    private final State state;

    AwaitingCloudInit(State state) {
      this.state = state;
    }

    public CloudInitDone during(
        String topic, Function<CloudInitTargetStage, CloudInitTargetStage> body) {
      final CloudInitTargetStage stage = new CloudInitTargetStage(state);
      state.runner.runDuring(topic, stage, body, state.onFailure);
      return new CloudInitDone(state);
    }
  }

  public static final class CloudInitDone {
    private final State state;

    CloudInitDone(State state) {
      this.state = state;
    }

    public AwaitingRegisteredComponents then() {
      return new AwaitingRegisteredComponents(state);
    }
  }

  public static final class AwaitingRegisteredComponents {
    private final State state;

    AwaitingRegisteredComponents(State state) {
      this.state = state;
    }

    public RegisteredComponentsDone during(
        String topic, Function<RegisteredComponentsStage, RegisteredComponentsStage> body) {
      final RegisteredComponentsStage stage = new RegisteredComponentsStage(state);
      state.runner.runDuring(topic, stage, body, state.onFailure);
      return new RegisteredComponentsDone(state);
    }
  }

  public static final class RegisteredComponentsDone {
    private final State state;

    RegisteredComponentsDone(State state) {
      this.state = state;
    }

    public Map<String, String> collectChecksums() {
      return Map.copyOf(state.targetChecksums);
    }
  }

  public static final class CloudInitTargetStage {
    private final State state;

    CloudInitTargetStage(State state) {
      this.state = state;
    }

    public CloudInitTargetStage fromCloudInitRoots() {
      // Cloud-init target: STATIC. Cloud-init reads the seed once at first boot, so any change
      // to the source ConfigMap means we recreate the instance.
      // runtimeCloudConfigRoot generates cloudSeedRoot (user-data/meta-data/network-config);
      // checksum only the input — output is deterministically derived.
      final List<Path> roots = List.of(state.paths.runtimeCloudConfigRoot());

      state.targetChecksums.put("cloud-init", computeChecksum(roots));
      return this;
    }
  }

  public static final class RegisteredComponentsStage {
    private final State state;

    RegisteredComponentsStage(State state) {
      this.state = state;
    }

    public RegisteredComponentsStage fromRegistry() {
      for (Map.Entry<String, List<Path>> entry : state.registry.getTargetRoots().entrySet()) {
        final String targetName = entry.getKey();
        state.targetChecksums.put(
            targetName, computeChecksum(entry.getValue(), targetName, state.registry));
      }
      return this;
    }
  }

  private static String computeChecksum(List<Path> roots) {
    return computeChecksum(roots, null, null);
  }

  private static String computeChecksum(
      List<Path> roots, String ownerName, ProvisioningTargetRegistry registry) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (Path root : roots) {
        final java.util.Set<Path> foreign =
            (ownerName != null && registry != null)
                ? registry.nestedForeignDescendants(root, ownerName)
                : java.util.Set.of();
        updateDigestForPath(digest, root, foreign);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
  }

  private static void updateDigestForPath(
      MessageDigest digest, Path root, java.util.Set<Path> foreignDescendants) {
    // NOTE: Do not include absolute path in digest — it contains ephemeral PID+timestamp from
    // the Maven build directory (host.12345.1779123456789). Hash only file contents and the
    // relative paths within the target's roots so checksums are deterministic.

    if (!Files.exists(root)) {
      digest.update("<missing>".getBytes(StandardCharsets.UTF_8));
      return;
    }

    if (Files.isRegularFile(root)) {
      digestFile(digest, root, root.getFileName());
      return;
    }

    try (Stream<Path> walk = Files.walk(root)) {
      walk.filter(Files::isRegularFile)
          .filter(file -> !isUnderForeign(file, foreignDescendants))
          .sorted()
          .forEach(file -> digestFile(digest, file, root.relativize(file)));
    } catch (IOException ex) {
      throw new IllegalStateException(
          "Failed to fingerprint provisioning resources at: " + root, ex);
    }
  }

  private static boolean isUnderForeign(Path file, java.util.Set<Path> foreignDescendants) {
    for (Path foreign : foreignDescendants) {
      if (file.startsWith(foreign)) {
        return true;
      }
    }
    return false;
  }

  private static void digestFile(MessageDigest digest, Path file, Path relativePath) {
    try {
      digest.update((byte) '\n');
      digest.update(relativePath.toString().getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      digest.update(Files.readAllBytes(file));
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to read provisioning resource: " + file, ex);
    }
  }
}
