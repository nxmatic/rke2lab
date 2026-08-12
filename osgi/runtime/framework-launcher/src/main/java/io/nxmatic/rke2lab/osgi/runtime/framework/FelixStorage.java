package io.nxmatic.rke2lab.osgi.runtime.framework;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Felix framework's on-disk storage dir, owned for the lifetime of ONE boot. Created under the
 * system temp dir and destroyed when the framework stops ({@link #delete()}). A JVM shutdown hook
 * is the backstop: a boot that never reaches its stop path — a killed test, a detached debugger, an
 * aborted CLI — still has its storage swept on exit, so runs cannot accrete the {@code
 * osgi-*-felix*} caches that otherwise silently fill the temp volume (Felix's {@code
 * STORAGE_CLEAN_ONFIRSTINIT} only cleans a dir it REUSES, and every boot mints a unique one).
 */
public final class FelixStorage {

  private static final Logger LOG = LoggerFactory.getLogger(FelixStorage.class);

  private final Path path;
  private final Thread shutdownHook;
  private final AtomicBoolean deleted = new AtomicBoolean(false);

  private FelixStorage(Path path) {
    this.path = path;
    this.shutdownHook = new Thread(() -> deleteTree(path), "felix-storage-cleanup");
    Runtime.getRuntime().addShutdownHook(shutdownHook);
  }

  /**
   * Mint a fresh storage dir under the temp volume, {@code prefix}ed for at-a-glance provenance.
   */
  public static FelixStorage create(String prefix) throws IOException {
    return new FelixStorage(Files.createTempDirectory(prefix));
  }

  public Path path() {
    return path;
  }

  /**
   * Recursively delete the storage and retire its shutdown hook. Idempotent: a second call — a
   * clean close after the hook already fired, a boot-failure cleanup racing the close — is a no-op.
   */
  public void delete() {
    if (!deleted.compareAndSet(false, true)) {
      return;
    }
    try {
      Runtime.getRuntime().removeShutdownHook(shutdownHook);
    } catch (IllegalStateException shuttingDown) {
      // JVM is already exiting; the hook is running (or about to) and sweeps the tree itself.
    }
    deleteTree(path);
  }

  private static void deleteTree(Path root) {
    if (!Files.exists(root)) {
      return;
    }
    try (var walk = Files.walk(root)) {
      walk.sorted(Comparator.reverseOrder()).forEach(FelixStorage::deleteQuietly);
    } catch (IOException ex) {
      LOG.warn("could not sweep Felix storage {}", root, ex);
    }
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ex) {
      LOG.warn("could not delete {}", path, ex);
    }
  }
}
