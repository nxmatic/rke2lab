package io.nxmatic.rke2lab.osgi.boot.discovery;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where a discovered bundle physically lives, and how to read it — the one thing that genuinely
 * DIFFERS between the two discovery sources, so it is the only thing split. A {@link BundleIndex}
 * over either source produces these; an executor installs from one without caring which source it
 * came from (it reads the manifest the same way, and installs by URL or by stream).
 *
 * <ul>
 *   <li>{@link OnClasspath} — a {@code java.class.path} entry: a jar, or an exploded {@code
 *       target/classes} dir during reactor builds. Installed by its file/reference URL.
 *   <li>{@link Staged} — a jar staged as a resource under {@code META-INF/bundles/} inside a
 *       deployed exec-jar (or an exploded resource root on the reactor test classpath), reached
 *       through the classloader that carries it. Installed by streaming its bytes into the cache.
 * </ul>
 */
public sealed interface BundleLocation {

  /** Read a manifest {@code header} from this bundle, or {@code null} if absent. */
  String readHeader(String header) throws IOException;

  /** Open this bundle's bytes — the caller closes the stream. */
  InputStream open() throws IOException;

  /** A stable id for this bundle in the framework's cache + logs (the install location string). */
  String locationId();

  /**
   * A {@code java.class.path} entry — a jar file or an exploded {@code target/classes} directory.
   */
  record OnClasspath(Path path) implements BundleLocation {
    @Override
    public String readHeader(String header) throws IOException {
      return BundleManifest.readHeader(path, header);
    }

    @Override
    public InputStream open() throws IOException {
      return Files.newInputStream(path);
    }

    @Override
    public String locationId() {
      // A directory bundle (exploded classes) installs via reference:; a packaged jar by its URI.
      return Files.isDirectory(path) ? "reference:" + path.toUri() : path.toUri().toString();
    }
  }

  /** A jar staged under {@code META-INF/bundles/<name>}, reached through {@code loader}. */
  record Staged(ClassLoader loader, String resourceName) implements BundleLocation {
    @Override
    public String readHeader(String header) throws IOException {
      try (InputStream in = open()) {
        return BundleManifest.readHeader(in, header);
      }
    }

    @Override
    public InputStream open() throws IOException {
      final InputStream in = loader.getResourceAsStream(resourceName);
      if (in == null) {
        throw new IOException("staged bundle not found on classpath: " + resourceName);
      }
      return in;
    }

    @Override
    public String locationId() {
      return resourceName;
    }
  }
}
