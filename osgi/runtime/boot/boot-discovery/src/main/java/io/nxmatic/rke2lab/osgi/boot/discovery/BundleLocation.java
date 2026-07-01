package io.nxmatic.rke2lab.osgi.boot.discovery;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

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

  /** The service file a launchable OSGi framework declares — how the launcher identifies itself. */
  String FRAMEWORK_FACTORY_SERVICE = "META-INF/services/org.osgi.framework.launch.FrameworkFactory";

  /** Read this bundle's manifest, or empty if it has none. The only read of the bytes. */
  Optional<Manifest> readManifest() throws IOException;

  /** Open this bundle's bytes — the caller closes the stream. */
  InputStream open() throws IOException;

  /** A stable id for this bundle in the framework's cache + logs (the install location string). */
  String locationId();

  /**
   * Whether this jar IS the OSGi framework launcher (it carries the {@link
   * #FRAMEWORK_FACTORY_SERVICE}). The launcher becomes the system bundle 0 — it is never installed
   * as an ordinary bundle, so the index excludes it. Identified by the service it declares, the
   * same honest signal {@code ServiceLoader.load(FrameworkFactory.class)} uses, never by a name.
   */
  boolean isFrameworkLauncher() throws IOException;

  /**
   * A {@code java.class.path} entry — a jar file or an exploded {@code target/classes} directory.
   */
  record OnClasspath(Path path) implements BundleLocation {
    @Override
    public Optional<Manifest> readManifest() throws IOException {
      if (Files.isDirectory(path)) {
        final Path mf = path.resolve("META-INF/MANIFEST.MF");
        if (!Files.exists(mf)) {
          return Optional.empty();
        }
        try (InputStream in = Files.newInputStream(mf)) {
          return Optional.of(new Manifest(in));
        }
      }
      try (JarFile jar = new JarFile(path.toFile())) {
        return Optional.ofNullable(jar.getManifest());
      }
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

    @Override
    public boolean isFrameworkLauncher() throws IOException {
      if (Files.isDirectory(path)) {
        return Files.exists(path.resolve(FRAMEWORK_FACTORY_SERVICE));
      }
      try (JarFile jar = new JarFile(path.toFile())) {
        return jar.getEntry(FRAMEWORK_FACTORY_SERVICE) != null;
      }
    }
  }

  /** A jar staged under {@code META-INF/bundles/<name>}, reached through {@code loader}. */
  record Staged(ClassLoader loader, String resourceName) implements BundleLocation {
    @Override
    public Optional<Manifest> readManifest() throws IOException {
      try (JarInputStream jar = new JarInputStream(open())) {
        return Optional.ofNullable(jar.getManifest());
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

    @Override
    public boolean isFrameworkLauncher() throws IOException {
      // Staged jars live under META-INF/bundles/; the launcher is flat in the uber-jar, never
      // staged, so a staged jar is by construction not the launcher. Checked honestly all the same.
      try (JarInputStream jar = new JarInputStream(open())) {
        for (var entry = jar.getNextJarEntry(); entry != null; entry = jar.getNextJarEntry()) {
          if (FRAMEWORK_FACTORY_SERVICE.equals(entry.getName())) {
            return true;
          }
        }
      }
      return false;
    }
  }
}
