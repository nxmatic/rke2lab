package io.nxmatic.rke2lab.cdk8s.systemd;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
}
