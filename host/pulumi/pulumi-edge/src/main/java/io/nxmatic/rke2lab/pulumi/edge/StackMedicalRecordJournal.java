package io.nxmatic.rke2lab.pulumi.edge;

import io.nxmatic.rke2lab.doctor.port.MedicalRecordJournal;
import io.nxmatic.rke2lab.doctor.records.DoctorCoordinate;
import io.nxmatic.rke2lab.doctor.records.Patient;
import io.nxmatic.rke2lab.doctor.records.VisitWire;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.Role;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * The Pulumi file-backend implementation of the host {@link MedicalRecordJournal} READ port
 * (Layer-1): it walks a patient's stack history and wraps each readable entry's RAW
 * consultation-report and expectation output blobs into one opaque {@code visit} {@link
 * SeedEnvelope}, WITHOUT interpreting the medical content. OSGi rebuilds the {@code MedicalRecord}
 * from these blobs inside the bundle realm.
 *
 * <p>This is the Layer-1 half of what {@code LiveMedicalRecordRegistry} + {@code
 * MedicalRecordReader} used to do host-side: the timeline walk and the per-entry output harvest
 * stay here (stack knowledge); the blob→record fold moved OSGi-side. A per-entry snapshot read
 * failure degrades to skipping that entry with a logged reason — the diagnosis path tolerates a
 * partial record, matching the registry's old fail-at-end-into-partial stance. An absent backend
 * yields an empty history.
 */
public final class StackMedicalRecordJournal implements MedicalRecordJournal {

  private static final String BACKEND_URL_ENV = "PULUMI_BACKEND_URL";
  private static final String FILE_SCHEME = "file://";

  private final SeedCodec codec = new SeedCodec();
  private final Optional<Path> backendDir;
  private final Consumer<String> logger;

  public StackMedicalRecordJournal(Optional<Path> backendDir, Consumer<String> logger) {
    this.backendDir = backendDir;
    this.logger = logger;
  }

  public static StackMedicalRecordJournal fromEnvironment(Consumer<String> logger) {
    return new StackMedicalRecordJournal(backendDirFromUrl(System.getenv(BACKEND_URL_ENV)), logger);
  }

  static Optional<Path> backendDirFromUrl(@Nullable String pulumiBackendUrl) {
    return Optional.ofNullable(pulumiBackendUrl)
        .filter(url -> url.startsWith(FILE_SCHEME))
        .map(url -> Path.of(url.substring(FILE_SCHEME.length())));
  }

  /** The file-backend root this journal reads from, or empty when no file:// backend is set. */
  public Optional<Path> backendDir() {
    return backendDir;
  }

  @Override
  public List<SeedEnvelope> historyOf(Patient patient) {
    if (backendDir.isEmpty()) {
      logger.accept(
          "medical record empty for "
              + patient.qualifiedName()
              + ": no file:// PULUMI_BACKEND_URL configured");
      return List.of();
    }
    final Path root = backendDir.orElseThrow();
    final StackHandle handle = StackHandle.forBackend(root, patient.project(), patient.stack());

    final List<StackHistory.Entry> entries;
    try {
      entries = handle.history().entries();
    } catch (StackException e) {
      // The spine is the precondition for any reconstruction: with no readable timeline there is no
      // partial to build. A present-but-unreadable history is propagated, never masked as empty.
      throw new RuntimeException("medical record history present but unreadable under " + root, e);
    }

    final List<SeedEnvelope> journal = new ArrayList<>(entries.size());
    for (StackHistory.Entry entry : entries) {
      try {
        journal.add(visitEnvelope(entry, handle.snapshotOf(entry)));
      } catch (StackException e) {
        // A present entry that cannot be materialized degrades to a skip with a reason — the fold
        // continues on the readable prefix rather than throwing into the diagnosis path.
        logger.accept(
            "medical record entry skipped for "
                + patient.qualifiedName()
                + ": version="
                + entry.version()
                + " at "
                + entry.when()
                + " unreadable — "
                + e.getMessage());
      }
    }
    return journal;
  }

  @Override
  public List<Patient> cohort(Patient current) {
    if (backendDir.isEmpty()) {
      return List.of(current);
    }
    final Path stacksDir =
        PulumiBackendLayout.stacksDir(backendDir.orElseThrow(), current.project());
    if (!Files.isDirectory(stacksDir)) {
      return List.of(current);
    }
    try (Stream<Path> entries = Files.list(stacksDir)) {
      return entries
          .filter(Files::isDirectory)
          .map(dir -> dir.getFileName().toString())
          .map(stack -> new Patient(current.org(), current.project(), stack))
          .sorted(
              Comparator.comparing((Patient p) -> p.stack().equals(current.stack()) ? 0 : 1)
                  .thenComparing(Patient::stack))
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("cannot enumerate cohort under " + stacksDir, e);
    }
  }

  /**
   * The opaque {@code visit} {@link SeedEnvelope} for one history entry: the entry's version + when
   * plus the raw consultation-report and expectation output blobs, harvested by {@code
   * StackSnapshot.outputsNamed} under the {@link Role} keys the write frontier filed each scion
   * under (the split verb groups scions by role, and the frontier persists {@code role -> value},
   * so the read frontier harvests by the SAME role — write and read cannot drift). The blob lists
   * keep their exact per-resource shape; the host never parses them — it renders the whole {@link
   * VisitWire} through the codec.
   */
  private SeedEnvelope visitEnvelope(StackHistory.Entry entry, StackSnapshot snapshot) {
    final VisitWire visit =
        new VisitWire(
            entry.version(),
            entry.when(),
            snapshot.outputsNamed(Role.FRUIT),
            snapshot.outputsNamed(Role.SOWING));
    return SeedEnvelope.of(DoctorCoordinate.VISIT, codec.encode(visit));
  }
}
