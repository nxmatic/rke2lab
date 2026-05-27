package io.nxmatic.rk2lab.controlplane.incus;

import io.nxmatic.rk2lab.controlplane.incus.IncusResourceBootstrap.BootstrapPaths;
import io.nxmatic.rk2lab.controlplane.pipeline.OnFailure;
import io.nxmatic.rk2lab.controlplane.pipeline.TopicRunner;
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
 *   .during("core", core -> core.fromCoreRoots())
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

    public AwaitingCore onFailure(OnFailure onFailure) {
      state.onFailure = onFailure;
      return new AwaitingCore(state);
    }

    public CoreDone during(String topic, Function<CoreTargetStage, CoreTargetStage> body) {
      final CoreTargetStage stage = new CoreTargetStage(state);
      TopicRunner.runDuring("target-checksum", topic, stage, body, state.onFailure);
      return new CoreDone(state);
    }
  }

  public static final class AwaitingCore {
    private final State state;

    AwaitingCore(State state) {
      this.state = state;
    }

    public CoreDone during(String topic, Function<CoreTargetStage, CoreTargetStage> body) {
      final CoreTargetStage stage = new CoreTargetStage(state);
      TopicRunner.runDuring("target-checksum", topic, stage, body, state.onFailure);
      return new CoreDone(state);
    }
  }

  public static final class CoreDone {
    private final State state;

    CoreDone(State state) {
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
      TopicRunner.runDuring("target-checksum", topic, stage, body, state.onFailure);
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

  public static final class CoreTargetStage {
    private final State state;

    CoreTargetStage(State state) {
      this.state = state;
    }

    public CoreTargetStage fromCoreRoots() {
      // Core target: STATIC infrastructure (cloud-init source ConfigMap).
      // runtimeCloudConfigRoot generates cloudSeedRoot (user-data/meta-data/network-config);
      // checksum only the input — output is deterministically derived.
      final List<Path> coreRoots = List.of(state.paths.runtimeCloudConfigRoot());

      state.targetChecksums.put("core", computeChecksum(coreRoots));
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
        state.targetChecksums.put(entry.getKey(), computeChecksum(entry.getValue()));
      }
      return this;
    }
  }

  private static String computeChecksum(List<Path> roots) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (Path root : roots) {
        updateDigestForPath(digest, root);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
  }

  private static void updateDigestForPath(MessageDigest digest, Path root) {
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
          .sorted()
          .forEach(file -> digestFile(digest, file, root.relativize(file)));
    } catch (IOException ex) {
      throw new IllegalStateException(
          "Failed to fingerprint provisioning resources at: " + root, ex);
    }
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
