package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.pulumi.automation.StackHandle;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The Pulumi file-backend implementation of {@link MedicalRecordRegistry}. It reconstructs each
 * patient's record on first request through {@link MedicalRecordReader} and memoizes it for the
 * registry instance's lifetime — one read per patient per run.
 *
 * <p>Per the registry contract {@link #recordFor} never throws: a null backend or a {@link
 * MedicalRecordReconstructionException} degrades to a (possibly partial) record and logs one reason
 * line. The reader already builds a partial on failure, so we surface that rather than discarding
 * readable visits; the degrade is the registry's deliberate policy here, not a swallow.
 */
public final class LiveMedicalRecordRegistry implements MedicalRecordRegistry {

  private static final String BACKEND_URL_ENV = "PULUMI_BACKEND_URL";
  private static final String FILE_SCHEME = "file://";

  private final Path backendDir;
  private final Consumer<String> logger;
  private final Map<Patient, MedicalRecord> cache = new ConcurrentHashMap<>();

  public LiveMedicalRecordRegistry(Path backendDir, Consumer<String> logger) {
    this.backendDir = backendDir;
    this.logger = logger;
  }

  public static LiveMedicalRecordRegistry fromEnvironment(Consumer<String> logger) {
    return new LiveMedicalRecordRegistry(backendDirFromUrl(System.getenv(BACKEND_URL_ENV)), logger);
  }

  static Path backendDirFromUrl(String pulumiBackendUrl) {
    if (pulumiBackendUrl == null || !pulumiBackendUrl.startsWith(FILE_SCHEME)) {
      return null;
    }
    return Path.of(pulumiBackendUrl.substring(FILE_SCHEME.length()));
  }

  @Override
  public MedicalRecord recordFor(Patient patient) {
    return cache.computeIfAbsent(patient, this::reconstruct);
  }

  private MedicalRecord reconstruct(Patient patient) {
    if (backendDir == null) {
      return degrade(patient, "no file:// PULUMI_BACKEND_URL configured");
    }
    final StackHandle handle =
        StackHandle.forBackend(backendDir, patient.project(), patient.stack());
    try {
      final MedicalRecord record =
          new MedicalRecordReader(new StackHandleSnapshotSource(handle)).read(patient);
      if (record.visits().isEmpty()) {
        // A clean read of an absent/empty history is nothing-here, not a failure — but the registry
        // owes its caller a reason so "no history yet" is distinguishable from a read that broke.
        return degrade(patient, "no readable history under " + backendDir);
      }
      return record;
    } catch (MedicalRecordReconstructionException ex) {
      logger.accept(
          "medical record incomplete for "
              + patient.qualifiedName()
              + ": "
              + ex.getMessage()
              + " — some entries were unreadable, proceeding on the partial record");
      return ex.partialRecord();
    }
  }

  private MedicalRecord degrade(Patient patient, String reason) {
    logger.accept("medical record empty for " + patient.qualifiedName() + ": " + reason);
    return new MedicalRecord(patient, List.of());
  }
}
