package io.nxmatic.rke2lab.controlplane.incus;

import io.nxmatic.rke2lab.controlplane.incus.IncusResourceBootstrap.BootstrapPaths;
import io.nxmatic.rke2lab.pipeline.FluentTopicRunner;
import io.nxmatic.rke2lab.pipeline.OnFailure;
import io.nxmatic.rke2lab.pipeline.Topic;
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
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
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

    // The State owns the ambient; topics read it through state::paths / state::registry (Suppliers
    // that resolve here on each .get()), never a copied reference — same read-face as the flux.
    BootstrapPaths paths() {
      return paths;
    }

    ProvisioningTargetRegistry registry() {
      return registry;
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
      final CloudInitTargetStage stage =
          new CloudInitTargetStage(state::paths, state.targetChecksums::put);
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
      final CloudInitTargetStage stage =
          new CloudInitTargetStage(state::paths, state.targetChecksums::put);
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
      final RegisteredComponentsStage stage =
          new RegisteredComponentsStage(state::registry, state.targetChecksums::put);
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

  /**
   * The write-face shared by both checksum topics — each pushes named checksum entries, the owner
   * folds them into the accumulator map. A single-method sink; the topics never hold the state.
   */
  public interface ChecksumSink extends Topic.Sink {
    void checksum(String targetName, String checksum);
  }

  public static final class CloudInitTargetStage implements Topic.Execution {
    private final Supplier<BootstrapPaths> paths;
    private final ChecksumSink sink;

    CloudInitTargetStage(Supplier<BootstrapPaths> paths, ChecksumSink sink) {
      this.paths = paths;
      this.sink = sink;
    }

    @Override
    public String role() {
      return "cloud-init";
    }

    public CloudInitTargetStage fromCloudInitRoots() {
      final BootstrapPaths paths = this.paths.get();
      // Cloud-init target: STATIC. Cloud-init reads the seed once at first boot, so any change
      // to the source ConfigMap means we recreate the instance.
      // runtimeCloudConfigRoot generates cloudSeedRoot (user-data/meta-data/network-config);
      // checksum only the input — output is deterministically derived.
      final List<Path> roots = List.of(paths.runtimeCloudConfigRoot());

      sink.checksum("cloud-init", computeChecksum(roots));
      return this;
    }
  }

  public static final class RegisteredComponentsStage implements Topic.Execution {
    private final Supplier<ProvisioningTargetRegistry> registry;
    private final ChecksumSink sink;

    RegisteredComponentsStage(Supplier<ProvisioningTargetRegistry> registry, ChecksumSink sink) {
      this.registry = registry;
      this.sink = sink;
    }

    @Override
    public String role() {
      return "registered components";
    }

    public RegisteredComponentsStage fromRegistry() {
      final ProvisioningTargetRegistry registry = this.registry.get();
      for (Map.Entry<String, List<Path>> entry : registry.getTargetRoots().entrySet()) {
        final String targetName = entry.getKey();
        sink.checksum(
            targetName,
            computeChecksum(
                entry.getValue(), Optional.of(new ChecksumScope(targetName, registry))));
      }
      return this;
    }
  }

  /**
   * When a target's roots are checksummed against a registry, foreign descendants (files owned by
   * other targets that happen to nest under this target's roots) must be excluded — {@code
   * ownerName} and {@code registry} are only ever used together, so they travel as one scope.
   */
  private record ChecksumScope(String ownerName, ProvisioningTargetRegistry registry) {
    Set<Path> foreignDescendants(Path root) {
      return registry.nestedForeignDescendants(root, ownerName);
    }
  }

  private static String computeChecksum(List<Path> roots) {
    return computeChecksum(roots, Optional.empty());
  }

  private static String computeChecksum(List<Path> roots, Optional<ChecksumScope> scope) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (Path root : roots) {
        final Set<Path> foreign = scope.map(s -> s.foreignDescendants(root)).orElseGet(Set::of);
        updateDigestForPath(digest, root, foreign);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
  }

  private static void updateDigestForPath(
      MessageDigest digest, Path root, Set<Path> foreignDescendants) {
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

  private static boolean isUnderForeign(Path file, Set<Path> foreignDescendants) {
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
