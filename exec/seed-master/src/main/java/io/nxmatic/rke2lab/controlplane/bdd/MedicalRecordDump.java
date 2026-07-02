package io.nxmatic.rke2lab.controlplane.bdd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.nxmatic.rke2lab.doctor.port.MedicalRecordJournal;
import io.nxmatic.rke2lab.pulumi.edge.StackMedicalRecordJournal;
import io.nxmatic.rke2lab.world.gateway.codec.DocumentCodec;
import io.nxmatic.rke2lab.world.gateway.port.Document;
import io.nxmatic.rke2lab.world.gateway.port.Patient;
import io.nxmatic.rke2lab.world.gateway.port.VisitWire;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The offline entry point that serializes a {@link Patient}'s medical record as YAML — HOST-PURE:
 * it reads its OWN timeline through the host {@link MedicalRecordJournal} (the opaque {@code visit}
 * Documents the journal produces) and transcodes each visit's {@code consultationReport} blob
 * JSON→YAML with the host's OWN jackson. It never calls OSGi and never rebuilds a {@code
 * doctor.records} type: the stored blob already IS the {@code ConsultationReport.toOutputMap}
 * shape, so the YAML is byte-identical to a reconstruct-then-reserialize.
 *
 * <p>It is a lenient-but-informed caller: when every visit Document parses cleanly the full record
 * is emitted (exit 0); when a Document is malformed the readable visits are STILL emitted (the
 * partial) and the failures are surfaced (non-zero exit). The policy decision lives here, never
 * deported to a log, never a silent swallow.
 */
public final class MedicalRecordDump {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
  private static final DocumentCodec CODEC = new DocumentCodec();

  private MedicalRecordDump() {}

  /**
   * The serialized record plus the policy outcome: the YAML, an exit code, the surfaced failures.
   */
  public record Result(String yaml, int exitCode, List<String> failures) {
    public Result {
      failures = failures == null ? List.of() : List.copyOf(failures);
    }
  }

  /**
   * YAML view of the visit timeline: patient qualifiedName, then visits in journal order, each with
   * its version/when/reports. The visit skeleton is ordered (journal order, then
   * version/when/reports); the per-report key order follows the stored {@code
   * consultationReport.toOutputMap} blob as-is, so the YAML matches the producer's shape exactly.
   */
  public static String toYaml(Patient patient, List<Map<String, Object>> visits) {
    final LinkedHashMap<String, Object> root = new LinkedHashMap<>();
    root.put("patient", patient.qualifiedName());
    root.put("visits", visits);
    try {
      return YAML.writeValueAsString(root);
    } catch (IOException e) {
      // A LinkedHashMap of Strings/Lists/Maps is always serializable; a failure here is a defect,
      // not an operator-facing condition, so it is unchecked rather than folded into the policy.
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Reads the patient's visit Documents from the journal and decides the policy. On a clean read:
   * full YAML, exit 0. On a malformed visit Document: the readable visits are still worth dumping
   * (a history has value to the operator), so we emit the partial AND surface every unreadable
   * visit — the human is both served the partial and informed of what was lost. The {@code journal}
   * is injected so a test passes a fake; only {@code main} wires the real {@link
   * StackMedicalRecordJournal}.
   */
  public static Result dump(Patient patient, MedicalRecordJournal journal) {
    final List<Map<String, Object>> visits = new ArrayList<>();
    final List<String> failures = new ArrayList<>();
    for (Document entry : journal.historyOf(patient)) {
      try {
        visits.add(visitYaml(entry));
      } catch (RuntimeException e) {
        failures.add(describe(entry, e));
      }
    }
    return new Result(toYaml(patient, visits), failures.isEmpty() ? 0 : 1, failures);
  }

  /**
   * One visit's YAML node from its opaque Document: version + when + the stored consultation-report
   * blobs as {@code reports}. The codec decodes the {@link VisitWire}; the report blobs ARE the
   * {@code toOutputMap} shape, copied through verbatim (the host authored them; it does not
   * interpret them).
   */
  private static Map<String, Object> visitYaml(Document entry) {
    final VisitWire visit = CODEC.decode(entry, VisitWire.class);
    final LinkedHashMap<String, Object> node = new LinkedHashMap<>();
    node.put("version", visit.version());
    node.put("when", visit.when().toString());
    node.put("reports", visit.consultationReport());
    return node;
  }

  private static String describe(Document entry, RuntimeException cause) {
    return "unreadable visit Document (" + entry.coordinate() + "): " + cause.getMessage();
  }

  public static void main(String[] args) {
    final Args parsed = Args.parse(args);
    final MedicalRecordJournal journal =
        new StackMedicalRecordJournal(Optional.of(parsed.backend), msg -> System.err.println(msg));
    final Result result = dump(parsed.patient(), journal);

    if (parsed.out.isEmpty()) {
      System.out.print(result.yaml());
    } else {
      try {
        Files.writeString(parsed.out.get(), result.yaml());
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    result.failures().forEach(line -> System.err.println("unreadable entry: " + line));
    System.exit(result.exitCode());
  }

  private record Args(String stack, Path backend, String project, String org, Optional<Path> out) {

    Patient patient() {
      return new Patient(org, project, stack);
    }

    static Args parse(String[] args) {
      String stack = null;
      Path backend = null;
      String project = null;
      String org = "organization";
      Optional<Path> out = Optional.empty();
      for (int i = 0; i < args.length; i++) {
        final String flag = args[i];
        switch (flag) {
          case "--stack" -> stack = value(args, ++i, flag);
          case "--backend" -> backend = Path.of(value(args, ++i, flag));
          case "--project" -> project = value(args, ++i, flag);
          case "--org" -> org = value(args, ++i, flag);
          case "--out" -> out = Optional.of(Path.of(value(args, ++i, flag)));
          default -> throw new IllegalArgumentException("unknown flag: " + flag);
        }
      }
      if (stack == null || backend == null || project == null) {
        throw new IllegalArgumentException(
            "usage: --stack <s> --backend <dir> --project <p> [--org <o>] [--out <file>]");
      }
      return new Args(stack, backend, project, org, out);
    }

    private static String value(String[] args, int i, String flag) {
      if (i >= args.length) {
        throw new IllegalArgumentException("missing value for flag: " + flag);
      }
      return args[i];
    }
  }
}
