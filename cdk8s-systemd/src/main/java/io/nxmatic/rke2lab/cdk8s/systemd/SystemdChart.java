package io.nxmatic.rke2lab.cdk8s.systemd;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import software.constructs.Construct;

/**
 * A Chart for systemd units.
 *
 * <p>Similar to {@code org.cdk8s.Chart} for Kubernetes, but synthesizes systemd unit files instead
 * of YAML manifests.
 *
 * <p><b>Usage</b>:
 *
 * <pre>{@code
 * App app = new App(AppProps.builder().outdir("/path/to/output").build());
 * SystemdChart chart = new SystemdChart(app, "systemd");
 *
 * new SystemdService(chart, "my-service")
 *     .description("My Service")
 *     .execStart("/usr/bin/my-app");
 *
 * app.synth(); // Synthesizes both K8s and systemd
 * }</pre>
 *
 * <p>When {@code app.synth()} is called, this chart synthesizes all systemd units as {@code
 * .service}, {@code .target}, {@code .timer} files in the app's output directory.
 */
public class SystemdChart extends Construct {

  private final List<SystemdUnit> units = new ArrayList<>();
  private final Map<String, List<String>> targetWantsRegistry = new LinkedHashMap<>();

  public SystemdChart(Construct scope, String id) {
    super(scope, id);
  }

  /**
   * Called by child {@link SystemdUnit} constructs when they're created.
   *
   * <p>Package-private - only units in this package can register themselves.
   */
  void registerUnit(SystemdUnit unit) {
    units.add(unit);
  }

  /**
   * Synthesizes all registered units to files.
   *
   * <p>This is called automatically by CDK8s {@code App.synth()} via the construct tree traversal.
   *
   * @param outdir the output directory (from App's outdir property)
   * @throws IOException if file writing fails
   */
  public void synthesize(Path outdir) throws IOException {
    Files.createDirectories(outdir);

    for (SystemdUnit unit : units) {
      final Path unitFile = outdir.resolve(unit.getUnitFileName());
      try (Writer writer = Files.newBufferedWriter(unitFile)) {
        unit.writeUnitFile(writer);
      }
    }
  }

  /**
   * Returns all registered units.
   *
   * <p>Useful for testing or inspection.
   */
  public List<SystemdUnit> getUnits() {
    return List.copyOf(units);
  }

  /**
   * Looks up a unit by its construct ID.
   *
   * <p>Returns null if not found.
   *
   * @param id the construct ID (without suffix, e.g., "rke2lab-nix-install")
   * @return the unit, or null if not found
   */
  public SystemdUnit findUnit(String id) {
    for (SystemdUnit unit : units) {
      if (unit.getUnitId().equals(id)) {
        return unit;
      }
    }
    return null;
  }

  /**
   * Registers a service with a target for automatic Wants= directive.
   *
   * <p>When a service calls {@code .wantedBy("some.target")}, this method tracks that relationship
   * so that {@link #finalizeTargetDependencies()} can add reciprocal {@code Wants=some.service} to
   * the target.
   *
   * <p>This makes the target-service hierarchy visible in {@code systemctl list-dependencies}.
   *
   * @param serviceName the service unit filename (e.g., "rke2lab-install.service")
   * @param targetName the target unit filename (e.g., "rke2lab-bootstrap.target")
   */
  public void registerServiceWithTarget(String serviceName, String targetName) {
    targetWantsRegistry.computeIfAbsent(targetName, k -> new ArrayList<>()).add(serviceName);
  }

  /**
   * Finalizes target dependencies by adding Wants= directives for registered services.
   *
   * <p>Call this AFTER all units have been synthesized but BEFORE {@link #synthesize(Path)}.
   *
   * <p>This creates the reciprocal relationship:
   *
   * <ul>
   *   <li>Services have {@code WantedBy=some.target} (enable symlink)
   *   <li>Targets have {@code Wants=some.service} (runtime dependency, visible in list-dependencies)
   * </ul>
   */
  public void finalizeTargetDependencies() {
    for (Map.Entry<String, List<String>> entry : targetWantsRegistry.entrySet()) {
      String targetName = entry.getKey();
      List<String> serviceNames = entry.getValue();

      // Find target by unit filename
      SystemdUnit targetUnit = null;
      for (SystemdUnit unit : units) {
        if (unit.getUnitFileName().equals(targetName)) {
          targetUnit = unit;
          break;
        }
      }

      if (targetUnit != null) {
        // Add Wants= directives for all registered services
        targetUnit.wants(serviceNames.toArray(String[]::new));
      }
    }
  }
}
