package io.nxmatic.rke2lab.controlplane.bdd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.nxmatic.rke2lab.doctor.port.ConsultationReport;
import io.nxmatic.rke2lab.doctor.port.MedicalRecord;
import io.nxmatic.rke2lab.doctor.port.MedicalRecordReader;
import io.nxmatic.rke2lab.doctor.port.MedicalRecordReconstructionException;
import io.nxmatic.rke2lab.doctor.port.MedicalRecordReconstructionException.EntryFailure;
import io.nxmatic.rke2lab.doctor.port.Patient;
import io.nxmatic.rke2lab.doctor.port.SnapshotException;
import io.nxmatic.rke2lab.doctor.port.SnapshotSource;
import io.nxmatic.rke2lab.pulumi.edge.StackHandle;
import io.nxmatic.rke2lab.pulumi.edge.StackHandleSnapshotSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The offline entry point that serializes a {@link Patient}'s medical record as YAML. It is the
 * FIRST lenient caller of the reconstruction: when the timeline reads cleanly the full record is
 * emitted (exit 0); when some entries are unreadable the partial record is STILL emitted and the
 * per-entry failures are surfaced (non-zero exit). The policy decision lives here, in the caller —
 * never deported to a log, never a silent swallow.
 */
public final class MedicalRecordDump {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

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
   * YAML view of a record: patient qualifiedName, then visits in record order, each with its
   * version/when/reports. The visit skeleton is ordered (record order, then version/when/reports);
   * the per-report key order follows {@link ConsultationReport#toOutputMap()} as-is.
   */
  public static String toYaml(MedicalRecord record) {
    final LinkedHashMap<String, Object> root = new LinkedHashMap<>();
    root.put("patient", record.patient().qualifiedName());
    root.put(
        "visits",
        record.visits().stream()
            .map(
                visit -> {
                  final LinkedHashMap<String, Object> node = new LinkedHashMap<>();
                  node.put("version", visit.version());
                  node.put("when", visit.when().toString());
                  node.put(
                      "reports",
                      visit.reports().stream().map(ConsultationReport::toOutputMap).toList());
                  return (Map<String, Object>) node;
                })
            .toList());
    try {
      return YAML.writeValueAsString(root);
    } catch (IOException e) {
      // A LinkedHashMap of Strings/Lists/Maps is always serializable; a failure here is a defect,
      // not an operator-facing condition, so it is unchecked rather than folded into the policy.
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Assembles the reader over the injected {@code source}, reads the record, and decides the
   * policy. On a clean read: full YAML, exit 0. On reconstruction failure: the partial record is
   * still worth dumping (a readable history of visits has value to the operator), so we emit {@code
   * toYaml(partialRecord)} AND surface every suppressed {@link EntryFailure} — the human is both
   * served the partial and informed of what was lost. The {@code source} is injected so a test
   * passes a fake; only {@code main} wires the real {@link StackHandleSnapshotSource}.
   */
  public static Result dump(Patient patient, SnapshotSource source) {
    final MedicalRecordReader reader = new MedicalRecordReader(source);
    try {
      return new Result(toYaml(reader.read(patient)), 0, List.of());
    } catch (MedicalRecordReconstructionException ex) {
      return new Result(toYaml(ex.partialRecord()), 1, describe(ex));
    }
  }

  private static List<String> describe(MedicalRecordReconstructionException ex) {
    final List<String> lines = new ArrayList<>();
    for (Throwable suppressed : ex.getSuppressed()) {
      if (suppressed instanceof EntryFailure failure) {
        lines.add(
            "version=" + failure.version() + " at " + failure.when() + " — " + causePath(failure));
      } else {
        lines.add(causePath(suppressed));
      }
    }
    return lines;
  }

  private static String causePath(Throwable failure) {
    final Throwable cause = failure.getCause();
    if (cause instanceof SnapshotException snapshot) {
      return snapshot.location() + ": " + snapshot.getMessage();
    }
    return failure.getMessage();
  }

  public static void main(String[] args) {
    final Args parsed = Args.parse(args);
    final StackHandle handle =
        StackHandle.attach(parsed.stack, parsed.workdir, parsed.backend, parsed.project);
    final Result result = dump(parsed.patient(), new StackHandleSnapshotSource(handle));

    if (parsed.out == null) {
      System.out.print(result.yaml());
    } else {
      try {
        Files.writeString(parsed.out, result.yaml());
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    result.failures().forEach(line -> System.err.println("unreadable entry: " + line));
    System.exit(result.exitCode());
  }

  private record Args(
      String stack, Path workdir, Path backend, String project, String org, Path out) {

    Patient patient() {
      return new Patient(org, project, stack);
    }

    static Args parse(String[] args) {
      String stack = null;
      Path workdir = null;
      Path backend = null;
      String project = null;
      String org = "organization";
      Path out = null;
      for (int i = 0; i < args.length; i++) {
        final String flag = args[i];
        switch (flag) {
          case "--stack" -> stack = value(args, ++i, flag);
          case "--workdir" -> workdir = Path.of(value(args, ++i, flag));
          case "--backend" -> backend = Path.of(value(args, ++i, flag));
          case "--project" -> project = value(args, ++i, flag);
          case "--org" -> org = value(args, ++i, flag);
          case "--out" -> out = Path.of(value(args, ++i, flag));
          default -> throw new IllegalArgumentException("unknown flag: " + flag);
        }
      }
      if (stack == null || workdir == null || backend == null || project == null) {
        throw new IllegalArgumentException(
            "usage: --stack <s> --workdir <dir> --backend <dir> --project <p> [--org <o>] [--out <file>]");
      }
      return new Args(stack, workdir, backend, project, org, out);
    }

    private static String value(String[] args, int i, String flag) {
      if (i >= args.length) {
        throw new IllegalArgumentException("missing value for flag: " + flag);
      }
      return args[i];
    }
  }
}
