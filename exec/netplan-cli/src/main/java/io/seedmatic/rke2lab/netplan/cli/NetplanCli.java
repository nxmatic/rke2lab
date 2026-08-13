package io.seedmatic.rke2lab.netplan.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.tngtech.jgiven.report.model.ExecutionStatus;
import com.tngtech.jgiven.report.model.ReportModel;
import io.seedmatic.rke2lab.netplan.cli.bdd.NetplanCliRun;
import io.seedmatic.rke2lab.netplan.cli.bdd.NetplanCliScenario;
import io.seedmatic.rke2lab.osgi.runtime.junit.launcher.JUnitLauncherCore;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.LogFileSeed;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.RunRole;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.RunRoleSeed;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioOutcomeSeed;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.TxIdSeed;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Command-line interface for netplan operations.
 *
 * <p>The {@code yamlExport} verb drives {@link NetplanCliScenario} on the embedded JUnit launcher —
 * the SAME BDD-as-engine machinery {@code seed-master} and {@code manifests-cli} use. The scenario
 * sows the {@code netplan} coordinate through the broker; that grows {@code
 * NetplanBlueprintScenario} in-container, where {@code ClusterNetworkBlueprint} (a {@code
 * type=contract} bundle record the flat host cannot reference) is reachable. The scion writes
 * {@code blueprint.json} into a SOIL temp dir; this CLI reads that host-neutral JSON back and
 * converts it to YAML — no contract type ever crosses to the host (the old flat {@code
 * BlueprintExportCommand} was a realm-boundary violation that {@code NoClassDefFoundError}ed since
 * the {@code netplan.api}→{@code contract} rename).
 *
 * <p>Supported commands:
 *
 * <ul>
 *   <li><b>yamlExport</b> — export complete network blueprint metadata as YAML (the rke2lab→
 *       nix-darwin-home bridge: the flake's {@code rke2lab.lib.networkBlueprint} is parsed from it)
 *   <li><b>jsonSchemaExport</b> — export JSON schema for blueprint validation (not yet implemented)
 * </ul>
 */
public final class NetplanCli {

  private static final Logger LOG = LoggerFactory.getLogger(NetplanCli.class);

  private NetplanCli() {}

  public static void main(String[] args) throws Exception {
    final String commandName = args.length > 0 ? args[0] : "";

    switch (commandName) {
      case "yamlExport" -> exportBlueprintYaml();
      case "jsonSchemaExport" -> {
        LOG.error("jsonSchemaExport not yet implemented");
        System.exit(1);
      }
      default -> {
        LOG.error(
            "specify a command — supported: yamlExport, jsonSchemaExport (got: '{}')", commandName);
        System.exit(1);
      }
    }
  }

  /**
   * Drive {@link NetplanCliScenario} to grow the blueprint export in-container into a SOIL temp
   * dir, then read that {@code blueprint.json} (generic JSON) and stream it to stdout as YAML. The
   * scenario noise stays off stdout at its own source — {@code ScenarioOutcomeExtension} silences
   * jGiven's console report (the outcome is the harvested runbook), and the framework log rides its
   * file appender — so stdout carries only the YAML. Only the DATA matters — nix-darwin-home
   * re-parses via {@code yq -o=json} — so the YAML carries no comments.
   */
  private static void exportBlueprintYaml() {
    final Path soil = freshExportDir();
    try {
      playExport(NetplanCliRun.of(Optional.of(soil.toString())));
      final JsonNode blueprint = readBlueprint(soil.resolve("blueprint.json"));
      writeYaml(blueprint, System.out);
    } finally {
      deleteRecursively(soil);
    }
  }

  private static void playExport(NetplanCliRun run) {
    final String txId = UUID.randomUUID().toString();
    try {
      final ReportModel runbook =
          new JUnitLauncherCore<ReportModel>()
              .run(
                  NetplanCli.class.getClassLoader(),
                  JupiterTestEngine.class,
                  wiring -> List.of(DiscoverySelectors.selectClass(NetplanCliScenario.class)),
                  (launcher, request, sessionStore) -> {
                    final SummaryGeneratingListener listener = new SummaryGeneratingListener();
                    launcher.execute(request, listener);
                    final var summary = listener.getSummary();
                    if (summary.getTotalFailureCount() > 0) {
                      final var first = summary.getFailures().get(0);
                      throw new IllegalStateException(
                          "the netplan-cli scenario failed: "
                              + first.getTestIdentifier().getDisplayName(),
                          first.getException());
                    }
                    return new ScenarioOutcomeSeed().read(sessionStore).runbook();
                  },
                  NetplanCliScenario.SEED
                      .into(run)
                      .andThen(RunRoleSeed.into(RunRole.ROOT))
                      .andThen(TxIdSeed.into(txId))
                      .andThen(LogFileSeed.into(".local.d/netplan-cli.log")));
      final List<?> broken =
          runbook.getScenariosWithStatus(ExecutionStatus.FAILED, ExecutionStatus.ABORTED);
      if (!broken.isEmpty()) {
        throw new IllegalStateException(
            "the blueprint export did not complete (" + broken.size() + " failed/aborted)");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("the netplan-cli run was interrupted", interrupted);
    }
  }

  private static JsonNode readBlueprint(Path blueprintJson) {
    if (!Files.exists(blueprintJson)) {
      throw new IllegalStateException(
          "the netplan scion reaped no blueprint export at " + blueprintJson);
    }
    try {
      return new ObjectMapper().readTree(Files.readString(blueprintJson));
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot read the blueprint export " + blueprintJson, ex);
    }
  }

  private static void writeYaml(JsonNode blueprint, PrintStream out) {
    final YAMLFactory yamlFactory =
        YAMLFactory.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .build();
    try {
      new ObjectMapper(yamlFactory).writerWithDefaultPrettyPrinter().writeValue(out, blueprint);
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot render the blueprint as YAML", ex);
    }
  }

  private static Path freshExportDir() {
    try {
      return Files.createTempDirectory("rke2lab-netplan-export-").toAbsolutePath().normalize();
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot create the blueprint export dir", ex);
    }
  }

  private static void deleteRecursively(Path root) {
    if (!Files.exists(root)) {
      return;
    }
    try (var paths = Files.walk(root)) {
      paths
          .sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException ex) {
                  throw new UncheckedIOException("cannot clean the export dir " + path, ex);
                }
              });
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot walk the export dir " + root, ex);
    }
  }
}
