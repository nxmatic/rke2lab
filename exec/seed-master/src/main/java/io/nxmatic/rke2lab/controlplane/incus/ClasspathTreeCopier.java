package io.nxmatic.rke2lab.controlplane.incus;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Copies a classpath resource subtree to a filesystem directory. Handles both filesystem-backed
 * classpath roots (Maven exec-from-target/classes) and jar-backed roots (shaded jar at runtime).
 *
 * <p>One copy strategy, two callers — the host-systemd assets shipped via the manifests bundle and
 * any future ProvisioningTarget that materialises bundled resources at apply time.
 */
public final class ClasspathTreeCopier {

  private ClasspathTreeCopier() {}

  /**
   * Copy every regular file under {@code classpathRoot} to {@code targetDir}, preserving relative
   * paths. Existing files in the target are overwritten; the target is not cleared first (the
   * caller decides whether to clear).
   *
   * @param classpathRoot classpath path (no leading slash), e.g. {@code
   *     "META-INF/.../systemd/units"}
   * @param targetDir filesystem directory to copy into; created if missing
   * @param scriptsExecutable when true, files copied get the executable bit set
   * @throws IllegalStateException if the classpath root cannot be resolved
   * @throws IOException if any file operation fails
   */
  public static void copy(String classpathRoot, Path targetDir, boolean scriptsExecutable)
      throws IOException {
    // Resolve against THIS class's loader, not the thread context classloader. These are host
    // assets
    // shaded into the exec-jar that also holds this class, so its loader always sees them. The TCCL
    // is not a reliable root here: the call runs on a pipeline worker after the embedded Felix boot
    // and a cdk8s synthesis, and under the Pulumi runtime that worker's context classloader does
    // not
    // see the host uber-jar — getResource returns null though the resource is present (it resolves
    // fine standalone). The class loader is deterministic; the TCCL is ambient.
    final URL rootUrl = ClasspathTreeCopier.class.getClassLoader().getResource(classpathRoot);
    if (rootUrl == null) {
      throw new IllegalStateException("Classpath resource root not found: " + classpathRoot);
    }

    Files.createDirectories(targetDir);

    if ("jar".equals(rootUrl.getProtocol())) {
      copyFromJar(rootUrl, classpathRoot, targetDir, scriptsExecutable);
      return;
    }

    final Path filesystemRoot;
    try {
      filesystemRoot = Path.of(rootUrl.toURI());
    } catch (Exception ex) {
      throw new IOException("Failed to resolve classpath root URL: " + rootUrl, ex);
    }
    copyFromFilesystem(filesystemRoot, targetDir, scriptsExecutable);
  }

  private static void copyFromFilesystem(Path classpathRoot, Path targetDir, boolean executable)
      throws IOException {
    try (Stream<Path> walk = Files.walk(classpathRoot)) {
      walk.filter(Files::isRegularFile)
          .forEach(
              source -> {
                final Path relative = classpathRoot.relativize(source);
                final Path target = targetDir.resolve(relative.toString());
                copyOne(source, target, executable);
              });
    }
  }

  private static void copyFromJar(
      URL rootUrl, String classpathRoot, Path targetDir, boolean executable) throws IOException {
    // The classpath root may be an entry inside a shaded jar. Two paths to read it:
    //   - JarURLConnection (preferred for simple jar:file:...!/path)
    //   - mounted FileSystem (fallback for nested URI shapes)
    final URI uri;
    try {
      uri = rootUrl.toURI();
    } catch (Exception ex) {
      throw new IOException("Failed to resolve classpath root URI: " + rootUrl, ex);
    }

    final String entryPrefix = classpathRoot + "/";

    // Fast path: JarURLConnection enumerates entries by name without mounting a FileSystem.
    if (rootUrl.openConnection() instanceof JarURLConnection conn) {
      try (JarFile jar = conn.getJarFile()) {
        final Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
          final JarEntry entry = entries.nextElement();
          final String name = entry.getName();
          if (entry.isDirectory() || !name.startsWith(entryPrefix)) {
            continue;
          }
          final Path target = targetDir.resolve(name.substring(entryPrefix.length()));
          Files.createDirectories(target.getParent());
          try (InputStream in = jar.getInputStream(entry)) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
          }
          if (executable) {
            target.toFile().setExecutable(true, false);
          }
        }
      }
      return;
    }

    // Fallback: mount the jar as a FileSystem and walk it like a directory tree.
    try (FileSystem fs = FileSystems.newFileSystem(uri, Collections.emptyMap())) {
      copyFromFilesystem(fs.getPath(classpathRoot), targetDir, executable);
    }
  }

  private static void copyOne(Path source, Path target, boolean executable) {
    try {
      Files.createDirectories(target.getParent());
      Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
      if (executable) {
        target.toFile().setExecutable(true, false);
      }
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to copy classpath asset to " + target, ex);
    }
  }
}
