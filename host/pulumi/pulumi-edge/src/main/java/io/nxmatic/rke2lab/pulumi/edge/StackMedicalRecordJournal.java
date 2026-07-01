package io.nxmatic.rke2lab.pulumi.edge;

import io.nxmatic.rke2lab.doctor.port.MedicalRecordJournal;
import io.nxmatic.rke2lab.world.gateway.codec.DocumentCodec;
import io.nxmatic.rke2lab.world.gateway.port.Coordinate;
import io.nxmatic.rke2lab.world.gateway.port.Document;
import io.nxmatic.rke2lab.world.gateway.port.Domain;
import io.nxmatic.rke2lab.world.gateway.port.Patient;
import io.nxmatic.rke2lab.world.gateway.port.VisitWire;
import io.nxmatic.rke2lab.world.gateway.port.WorldGatewayCatalog;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The Pulumi file-backend implementation of the host {@link MedicalRecordJournal} READ port
 * (Layer-1): it walks a patient's stack history and wraps each readable entry's RAW
 * consultation-report and expectation output blobs into one opaque {@code visit} {@link Document},
 * WITHOUT interpreting the medical content. OSGi rebuilds the {@code MedicalRecord} from these
 * blobs inside the bundle realm.
 *
 * <p>This is the Layer-1 half of what {@code LiveMedicalRecordRegistry} + {@code
 * MedicalRecordReader} used to do host-side: the timeline walk and the per-entry output harvest
 * stay here (stack knowledge); the blob→record fold moved OSGi-side. A per-entry snapshot read
 * failure degrades to skipping that entry with a logged reason — the diagnosis path tolerates a
 * partial record, matching the registry's old fail-at-end-into-partial stance. A null backend
 * yields an empty history.
 */
public final class StackMedicalRecordJournal implements MedicalRecordJournal {

  private static final String BACKEND_URL_ENV = "PULUMI_BACKEND_URL";
  private static final String FILE_SCHEME = "file://";

  private final DocumentCodec codec = new DocumentCodec();
  private final Path backendDir;
  private final Consumer<String> logger;

  public StackMedicalRecordJournal(Path backendDir, Consumer<String> logger) {
    this.backendDir = backendDir;
    this.logger = logger;
  }

  public static StackMedicalRecordJournal fromEnvironment(Consumer<String> logger) {
    return new StackMedicalRecordJournal(backendDirFromUrl(System.getenv(BACKEND_URL_ENV)), logger);
  }

  static Path backendDirFromUrl(String pulumiBackendUrl) {
    if (pulumiBackendUrl == null || !pulumiBackendUrl.startsWith(FILE_SCHEME)) {
      return null;
    }
    return Path.of(pulumiBackendUrl.substring(FILE_SCHEME.length()));
  }

  /**
   * The file-backend root this journal reads from, or {@code null} when no file:// backend is
   * configured.
   */
  public Path backendDir() {
    return backendDir;
  }

  @Override
  public List<Document> historyOf(Patient patient) {
    if (backendDir == null) {
      logger.accept(
          "medical record empty for "
              + patient.qualifiedName()
              + ": no file:// PULUMI_BACKEND_URL configured");
      return List.of();
    }
    final StackHandle handle =
        StackHandle.forBackend(backendDir, patient.project(), patient.stack());

    final List<StackHistory.Entry> entries;
    try {
      entries = handle.history().entries();
    } catch (StackException e) {
      // The spine is the precondition for any reconstruction: with no readable timeline there is no
      // partial to build. A present-but-unreadable history is propagated, never masked as empty.
      throw new RuntimeException(
          "medical record history present but unreadable under " + backendDir, e);
    }

    final List<Document> journal = new ArrayList<>(entries.size());
    for (StackHistory.Entry entry : entries) {
      try {
        journal.add(visitDocument(entry, handle.snapshotOf(entry)));
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
    if (backendDir == null) {
      return List.of(current);
    }
    final Path stacksDir = PulumiBackendLayout.stacksDir(backendDir, current.project());
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
   * The opaque {@code visit} Document for one history entry: the entry's version + when plus the
   * raw consultation-report and expectation output blobs, harvested by {@code
   * StackSnapshot.outputsNamed} (the Pulumi output KEYS the resources wrote under — a host-internal
   * transport concern, hence still {@code WorldGatewayCatalog} names, not {@code VisitWire}
   * fields). The blob lists keep their exact per-resource shape; the host never parses them — it
   * renders the whole {@link VisitWire} through the codec.
   */
  private Document visitDocument(StackHistory.Entry entry, StackSnapshot snapshot) {
    final VisitWire visit =
        new VisitWire(
            entry.version(),
            entry.when(),
            snapshot.outputsNamed(WorldGatewayCatalog.FIELD_CONSULTATION_REPORT),
            snapshot.outputsNamed(WorldGatewayCatalog.FIELD_EXPECTATIONS));
    return new Document(Domain.DOCTOR.slug(), Coordinate.VISIT.slug(), codec.encode(visit));
  }
}
