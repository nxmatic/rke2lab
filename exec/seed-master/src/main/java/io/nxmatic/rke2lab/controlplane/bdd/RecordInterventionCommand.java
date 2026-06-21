package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.doctor.Intervention;
import io.nxmatic.rke2lab.doctor.InterventionLedgerWriter;
import io.nxmatic.rke2lab.doctor.ProblemRef;
import io.nxmatic.rke2lab.doctor.Provenance;
import io.nxmatic.rke2lab.doctor.RemediationProgramRef;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;

/**
 * The operator's declaration command. When the operator fixes something out-of-band (e.g. {@code
 * nft delete ...}), that fix leaves no trace in the stack, so the medical record keeps crediting a
 * prescription that was never applied. Running this command appends the intervention to the ledger,
 * closing that gap: the system learns WHO actually changed the world and stops attributing false
 * efficacy to its own engine.
 *
 * <p>The wall clock is never read in the core. {@link #record(String[], Instant,
 * InterventionLedgerWriter)} is the testable seam — args, the run instant, and the writer are all
 * injected; only {@link #main(String[])} supplies {@link Instant#now()} and the real {@link
 * PulumiInterventionLedgerWriter}.
 */
public final class RecordInterventionCommand {

  private RecordInterventionCommand() {}

  /**
   * Parses the arguments, builds the {@link Intervention}, appends it through the injected writer,
   * and returns it. {@code --when} defaults to the injected {@code now}, so the core stays free of
   * the wall clock.
   */
  static Intervention record(String[] args, Instant now, InterventionLedgerWriter writer) {
    final Args parsed = Args.parse(args);
    final Intervention intervention =
        new Intervention(
            parsed.provenance,
            parsed.when == null ? now : parsed.when,
            parsed.what,
            parsed.problem,
            parsed.prescriptionRef,
            Map.of());
    writer.append(intervention);
    return intervention;
  }

  public static void main(String[] args) {
    try {
      final Path backend = Args.backendOf(args);
      record(args, Instant.now(), new PulumiInterventionLedgerWriter(backend));
    } catch (IllegalArgumentException e) {
      System.err.println(e.getMessage());
      System.exit(2);
    }
  }

  private record Args(
      ProblemRef problem,
      String what,
      Provenance provenance,
      Optional<RemediationProgramRef> prescriptionRef,
      Instant when) {

    private static final String USAGE =
        "usage: --problem <checkpoint[/symptom]> --what <text>"
            + " [--provenance <id>] [--prescription-ref <id>] [--when <iso>] [--backend <dir>]";

    static Args parse(String[] args) {
      String problemArg = null;
      String what = null;
      String provenanceArg = null;
      String prescriptionArg = null;
      String whenArg = null;
      for (int i = 0; i < args.length; i++) {
        final String flag = args[i];
        switch (flag) {
          case "--problem" -> problemArg = value(args, ++i, flag);
          case "--what" -> what = value(args, ++i, flag);
          case "--provenance" -> provenanceArg = value(args, ++i, flag);
          case "--prescription-ref" -> prescriptionArg = value(args, ++i, flag);
          case "--when" -> whenArg = value(args, ++i, flag);
          case "--backend" -> i++;
          default -> throw new IllegalArgumentException("unknown flag: " + flag);
        }
      }
      final String problemRef = problemArg;
      final String provenanceRef = provenanceArg;
      final String prescriptionRefArg = prescriptionArg;
      if (problemRef == null) {
        throw new IllegalArgumentException("missing --problem; " + USAGE);
      }
      if (what == null) {
        throw new IllegalArgumentException("missing --what; " + USAGE);
      }
      final ProblemRef problem =
          ProblemRef.parse(problemRef)
              .orElseThrow(
                  () -> new IllegalArgumentException("unknown --problem reference: " + problemRef));
      final Provenance provenance =
          provenanceRef == null
              ? Provenance.OPERATOR_MANUAL
              : Provenance.parse(provenanceRef)
                  .orElseThrow(
                      () -> new IllegalArgumentException("unknown --provenance: " + provenanceRef));
      final Optional<RemediationProgramRef> prescriptionRef =
          prescriptionRefArg == null
              ? Optional.empty()
              : Optional.of(
                  RemediationProgramRef.parse(prescriptionRefArg)
                      .orElseThrow(
                          () ->
                              new IllegalArgumentException(
                                  "unknown --prescription-ref: " + prescriptionRefArg)));
      final Instant when = whenArg == null ? null : parseWhen(whenArg);
      return new Args(problem, what, provenance, prescriptionRef, when);
    }

    static Path backendOf(String[] args) {
      for (int i = 0; i < args.length; i++) {
        if ("--backend".equals(args[i])) {
          return Path.of(value(args, ++i, "--backend"));
        }
      }
      throw new IllegalArgumentException("missing --backend; " + USAGE);
    }

    private static String value(String[] args, int i, String flag) {
      if (i >= args.length) {
        throw new IllegalArgumentException("missing value for flag: " + flag);
      }
      return args[i];
    }

    // A malformed --when must reach the operator as the same usage error as any other bad flag, not
    // a raw DateTimeParseException stacktrace (main only catches IllegalArgumentException).
    private static Instant parseWhen(String whenArg) {
      try {
        return Instant.parse(whenArg);
      } catch (DateTimeParseException e) {
        throw new IllegalArgumentException(
            "invalid --when (expected ISO-8601 instant): " + whenArg);
      }
    }
  }
}
