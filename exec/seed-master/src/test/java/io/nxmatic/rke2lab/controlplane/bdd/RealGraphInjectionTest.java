package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.nxmatic.rke2lab.doctor.port.MedicalRecordReader;
import io.nxmatic.rke2lab.doctor.records.ConsultationReport;
import io.nxmatic.rke2lab.doctor.records.MedicalRecord;
import io.nxmatic.rke2lab.doctor.records.Observation;
import io.nxmatic.rke2lab.doctor.records.Prescription;
import io.nxmatic.rke2lab.doctor.records.RemediationPlan;
import io.nxmatic.rke2lab.doctor.records.RemediationProgramRef;
import io.nxmatic.rke2lab.doctor.records.Symptom;
import io.nxmatic.rke2lab.doctor.testkit.ReferralReplies;
import io.nxmatic.rke2lab.pulumi.edge.StackHandle;
import io.nxmatic.rke2lab.pulumi.edge.StackHandleSnapshotSource;
import io.nxmatic.rke2lab.pulumi.edge.StackHistory;
import io.nxmatic.rke2lab.pulumi.edge.testkit.StackHistoryFixture;
import io.nxmatic.rke2lab.world.gateway.port.Patient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves the reader extracts a diagnosis from a checkpoint of the real complexity it will meet at
 * runtime: a 23-node resource graph produced by another rke2lab version (dev's actual state). We
 * lift a real dev checkpoint's {@code latest} body, inject ONE tagged {@code consultationReport}
 * into its {@code SystemdAdapter} node — exactly where the doctor write-side would register it —
 * and read it back through the production pipeline.
 *
 * <p>Guarded by {@link org.junit.jupiter.api.Assumptions}: the dev backend is gitignored, machine-
 * local state, so this runs where it exists (a developer's checkout) and skips cleanly in CI. It is
 * the cross-version, real-data counterpart to the synthetic {@link SeededMedicalHistoryTest}.
 * {@code pulumi import} cannot feed this path (it writes no history entry — verified), so the
 * lifted graph is written as a history checkpoint the reader globs, not imported.
 */
class RealGraphInjectionTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String SEEDED_TAG = "synthetic — injected into a real dev graph for Task 14";

  @TempDir Path tempDir;

  @Test
  void extractsAnInjectedDiagnosisFromARealDevCheckpointGraph() throws Exception {
    final Path devHistory = devHistoryDir();
    assumeTrue(
        devHistory != null && Files.isDirectory(devHistory),
        "no local dev backend — skipping real-graph check");

    final Path realCheckpoint = newestCheckpoint(devHistory);
    assumeTrue(realCheckpoint != null, "no dev checkpoint file — skipping");

    // Lift the real latest body (the 23-node graph) and inject a tagged report into SystemdAdapter.
    final JsonNode root = JSON.readTree(realCheckpoint.toFile());
    final ObjectNode latest = (ObjectNode) root.path("checkpoint").path("latest");
    final ConsultationReport seeded =
        new ConsultationReport(
            "seeded-systemd-adapter",
            List.of(
                Observation.failed(
                    Symptom.CONNECTION_REFUSED,
                    "seeded into real dev graph",
                    Map.of("seeded", SEEDED_TAG))),
            new RemediationPlan(
                Symptom.CONNECTION_REFUSED,
                List.of(
                    ReferralReplies.treating(
                        Prescription.of(
                            RemediationProgramRef.RESTART_UNIT, Map.of(), "seeded hint"))),
                "seeded generalist summary"));
    injectIntoSystemdAdapter(latest, seeded);

    // Write the real-graph latest as a single history checkpoint the reader globs.
    final StackHistoryFixture fixture =
        StackHistoryFixture.at(tempDir, "rke2lab", "seeded")
            .updateWithLatest(1_780_000_100L, JSON.writeValueAsString(latest));

    final StackHandle handle = StackHandle.forBackend(fixture.backendDir(), "rke2lab", "seeded");
    final MedicalRecord record =
        new MedicalRecordReader(new StackHandleSnapshotSource(handle))
            .read(new Patient("organization", "rke2lab", "seeded"));

    // One visit, whose report was extracted from the real 23-node graph and is honestly tagged.
    assertEquals(1, record.visits().size());
    assertFalse(record.chiefComplaint().isEmpty());
    final ConsultationReport extracted = record.visits().get(0).reports().get(0);
    assertEquals(Symptom.CONNECTION_REFUSED, extracted.symptom());
    assertEquals(SEEDED_TAG, extracted.observations().get(0).details().get("seeded"));
  }

  /**
   * The dev backend's history dir. The {@code .pulumi/history/<project>/<stack>} layout is owned by
   * {@link StackHistory} — ask it, never re-encode the layout here (single source of truth). Only
   * the backend root ({@code .pulumi-state}) and which project/stack are local config. Resolved
   * against the multi-module project root, since surefire's CWD is the module basedir.
   */
  private static Path devHistoryDir() {
    final String root = System.getProperty("maven.multiModuleProjectDirectory");
    final Path base = root != null ? Path.of(root) : Path.of("").toAbsolutePath().getParent();
    if (base == null) {
      return null;
    }
    final Path backendRoot = base.resolve(".pulumi-state");
    return StackHistory.of(backendRoot, "rke2lab", "dev").historyDir();
  }

  private static Path newestCheckpoint(Path devHistory) throws Exception {
    try (Stream<Path> files = Files.list(devHistory)) {
      return files
          .filter(p -> p.getFileName().toString().endsWith(".checkpoint.json"))
          .max(Comparator.comparing(p -> p.getFileName().toString()))
          .orElse(null);
    }
  }

  /** Adds {@code outputs.consultationReport} to the first SystemdAdapter resource in the graph. */
  private static void injectIntoSystemdAdapter(ObjectNode latest, ConsultationReport report)
      throws Exception {
    final JsonNode reportNode = JSON.valueToTree(report.toOutputMap());
    for (JsonNode resource : latest.path("resources")) {
      if (resource.path("type").asText().contains("SystemdAdapter")) {
        final ObjectNode outputs = (ObjectNode) resource.path("outputs");
        outputs.set(ConsultationReport.OUTPUT_KEY, reportNode);
        return;
      }
    }
    throw new IllegalStateException(
        "no SystemdAdapter node in the lifted dev graph to inject into");
  }
}
