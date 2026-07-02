package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.doctor.port.InterventionIntake;
import io.nxmatic.rke2lab.doctor.port.InterventionLedgerWriter;
import io.nxmatic.rke2lab.osgi.runtime.FrameworkLaunchPipeline;
import io.nxmatic.rke2lab.pulumi.edge.PulumiInterventionLedgerWriter;
import io.nxmatic.rke2lab.world.gateway.codec.DocumentCodec;
import io.nxmatic.rke2lab.world.gateway.port.Coordinate;
import io.nxmatic.rke2lab.world.gateway.port.Document;
import io.nxmatic.rke2lab.world.gateway.port.Domain;
import io.nxmatic.rke2lab.world.gateway.port.InterventionRequest;
import io.nxmatic.rke2lab.world.gateway.port.ReadinessVerdict;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * The operator's declaration command. When the operator fixes something out-of-band (e.g. {@code
 * nft delete ...}), that fix leaves no trace in the stack, so the medical record keeps crediting a
 * prescription that was never applied. Running this command appends the intervention to the ledger,
 * closing that gap: the system learns WHO actually changed the world and stops attributing false
 * efficacy to its own engine.
 *
 * <p>Option A: the host holds NO doctor type. It parses argv to raw strings, builds an {@code
 * intervention-request} Document with its own jackson, and crosses the seam to the OSGi {@link
 * InterventionIntake} verb that owns the intervention schema — it canonicalizes (or rejects) the
 * facts and returns a canonical {@code intervention} Document, which the host then appends. The
 * doctor vocabulary ({@code ProblemRef} / {@code Provenance} / {@code RemediationProgramRef}) never
 * leaves the bundle.
 *
 * <p>The wall clock is never read in the core. {@link #record(String[], Instant,
 * InterventionIntake, InterventionLedgerWriter)} is the testable seam — args, the run instant, the
 * canonicalize handle, and the writer are all injected; only {@link #main(String[])} supplies
 * {@link Instant#now()}, boots the embedded framework for the real {@link InterventionIntake}, and
 * builds the real {@link PulumiInterventionLedgerWriter}.
 */
public final class RecordInterventionCommand {

  private static final DocumentCodec CODEC = new DocumentCodec();

  private RecordInterventionCommand() {}

  /**
   * Parses argv to raw strings (keeping the flat well-formedness checks so obvious mistakes error
   * early), builds the {@code intervention-request} Document, canonicalizes it through the OSGi
   * verb, and appends the canonical Document through the injected writer. {@code --when} defaults
   * to the injected {@code now}, so the core stays free of the wall clock.
   *
   * @return the canonical {@code intervention} Document that was appended
   * @throws InterventionRejected if the verb returns an error verdict (a bad reference)
   */
  static Document record(
      String[] args, Instant now, InterventionIntake intake, InterventionLedgerWriter writer) {
    final Args parsed = Args.parse(args);

    final InterventionRequest request =
        new InterventionRequest(
            parsed.problem(),
            parsed.what(),
            parsed.provenance(),
            parsed.prescriptionRef(),
            parsed.when().orElse(now));

    final Document rawFacts =
        new Document(
            Domain.DOCTOR.slug(), Coordinate.INTERVENTION_REQUEST.slug(), CODEC.encode(request));
    final Document canonical = intake.canonicalize(rawFacts);
    if (!Coordinate.INTERVENTION.slug().equals(canonical.coordinate())) {
      throw new InterventionRejected(reasonOf(canonical));
    }
    writer.append(canonical);
    return canonical;
  }

  public static void main(String[] args) {
    try {
      final Path backend = Args.backendOf(args);
      FrameworkLaunchPipeline.embedded()
          .during(
              "record-intervention",
              InterventionIntake.class,
              intake ->
                  record(args, Instant.now(), intake, new PulumiInterventionLedgerWriter(backend)));
    } catch (InterventionRejected rejected) {
      System.err.println(rejected.getMessage());
      System.exit(2);
    } catch (IllegalArgumentException e) {
      System.err.println(e.getMessage());
      System.exit(2);
    }
  }

  /** The OSGi verb rejected the facts (a bad reference); the operator must fix the argv. */
  static final class InterventionRejected extends RuntimeException {
    private static final long serialVersionUID = 1L;

    InterventionRejected(String reason) {
      super("intervention rejected: " + reason);
    }
  }

  private static String reasonOf(Document verdict) {
    try {
      return CODEC.decode(verdict, ReadinessVerdict.class).reason();
    } catch (RuntimeException e) {
      return "unparseable verdict payload";
    }
  }

  private record Args(
      String problem,
      String what,
      Optional<String> provenance,
      Optional<String> prescriptionRef,
      Optional<Instant> when) {

    private static final String USAGE =
        "usage: --problem <checkpoint[/symptom]> --what <text>"
            + " [--provenance <id>] [--prescription-ref <id>] [--when <iso>] [--backend <dir>]";

    static Args parse(String[] args) {
      String problem = null;
      String what = null;
      String provenance = null;
      String prescriptionRef = null;
      String whenArg = null;
      for (int i = 0; i < args.length; i++) {
        final String flag = args[i];
        switch (flag) {
          case "--problem" -> problem = value(args, ++i, flag);
          case "--what" -> what = value(args, ++i, flag);
          case "--provenance" -> provenance = value(args, ++i, flag);
          case "--prescription-ref" -> prescriptionRef = value(args, ++i, flag);
          case "--when" -> whenArg = value(args, ++i, flag);
          case "--backend" -> i++;
          default -> throw new IllegalArgumentException("unknown flag: " + flag);
        }
      }
      if (problem == null) {
        throw new IllegalArgumentException("missing --problem; " + USAGE);
      }
      if (what == null) {
        throw new IllegalArgumentException("missing --what; " + USAGE);
      }
      return new Args(
          problem,
          what,
          Optional.ofNullable(provenance),
          Optional.ofNullable(prescriptionRef),
          Optional.ofNullable(whenArg).map(Args::parseWhen));
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
