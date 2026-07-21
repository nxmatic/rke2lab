package io.nxmatic.rke2lab.manifests.systemd;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * Loads the rke2lab host scripts bundled in manifests-core ({@code systemd/systemd-scripts/*.sh})
 * as {@code fileName -> content}, so they can be registered as {@link
 * io.nxmatic.rke2lab.systemd.cdk8s.SystemdScript} constructs on the chart (the chart owns the
 * systemd bundle; the content is manifests-core's). Enumeration is dual-path: the bundle's {@code
 * findEntries} in OSGi, a classpath walk (filesystem or jar) in a plain JVM (the CLI /
 * out-of-container synthesis) — a resource directory has no listing without one of these.
 */
final class SystemdScriptResources {

  private static final String RESOURCE_DIR = "systemd/systemd-scripts";

  private SystemdScriptResources() {}

  static Map<String, String> load() {
    final Bundle bundle = FrameworkUtil.getBundle(SystemdScriptResources.class);
    return bundle != null ? loadFromBundle(bundle) : loadFromClasspath();
  }

  private static Map<String, String> loadFromBundle(Bundle bundle) {
    final Map<String, String> scripts = new TreeMap<>();
    final Enumeration<URL> entries = bundle.findEntries("/" + RESOURCE_DIR, "*.sh", false);
    if (entries == null) {
      return scripts;
    }
    while (entries.hasMoreElements()) {
      final URL entry = entries.nextElement();
      scripts.put(fileName(entry.getPath()), read(entry));
    }
    return scripts;
  }

  private static Map<String, String> loadFromClasspath() {
    final URL root = Thread.currentThread().getContextClassLoader().getResource(RESOURCE_DIR);
    if (root == null) {
      return Map.of();
    }
    if ("jar".equals(root.getProtocol())) {
      return loadFromJar(root);
    }
    try (Stream<Path> walk = Files.list(Path.of(root.toURI()))) {
      final Map<String, String> scripts = new TreeMap<>();
      walk.filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(".sh"))
          .forEach(path -> scripts.put(path.getFileName().toString(), readFile(path)));
      return scripts;
    } catch (Exception ex) {
      throw new UncheckedIOException(
          new IOException("failed to list systemd scripts at " + root, ex));
    }
  }

  private static Map<String, String> loadFromJar(URL root) {
    final String prefix = RESOURCE_DIR + "/";
    final Map<String, String> scripts = new TreeMap<>();
    try {
      final JarURLConnection connection = (JarURLConnection) root.openConnection();
      try (JarFile jar = connection.getJarFile()) {
        final Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
          final JarEntry entry = entries.nextElement();
          final String name = entry.getName();
          if (entry.isDirectory() || !name.startsWith(prefix) || !name.endsWith(".sh")) {
            continue;
          }
          try (InputStream in = jar.getInputStream(entry)) {
            scripts.put(fileName(name), new String(in.readAllBytes(), StandardCharsets.UTF_8));
          }
        }
      }
    } catch (IOException ex) {
      throw new UncheckedIOException("failed to read systemd scripts jar at " + root, ex);
    }
    return scripts;
  }

  private static String fileName(String path) {
    final int slash = path.lastIndexOf('/');
    return slash < 0 ? path : path.substring(slash + 1);
  }

  private static String read(URL url) {
    try (InputStream in = url.openStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new UncheckedIOException("failed to read systemd script " + url, ex);
    }
  }

  private static String readFile(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException ex) {
      throw new UncheckedIOException("failed to read systemd script " + path, ex);
    }
  }
}
