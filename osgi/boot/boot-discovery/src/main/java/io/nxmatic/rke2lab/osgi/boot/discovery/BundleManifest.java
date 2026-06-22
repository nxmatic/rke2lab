package io.nxmatic.rke2lab.osgi.boot.discovery;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

/**
 * Pure reads of an OSGi bundle manifest — header extraction + clause parsing — with NO framework
 * dependency. Shared by every part of the boot model so the same parsing serves both discovery
 * sources: a bundle located on the classpath (a {@link Path} to a jar or exploded {@code
 * target/classes} dir) and an embedded bundle streamed from {@code META-INF/bundles/} (an {@link
 * InputStream}). Static because every method is a pure function of its argument — the one case the
 * instance-passing rule exempts.
 */
public final class BundleManifest {

  /**
   * The {@code Provide-Capability} namespace a bundle self-declares (in its {@code bnd.bnd}) to
   * mark itself an embeddable MODEL bundle to install — the single source of truth for "which jars
   * are ours to embed". The boot-stack jars (pax / felix.scr / felix.resolver) do not carry it, so
   * they are excluded objectively, with no parallel hand-list of names. A namespace with no
   * matching {@code Require-Capability}, so the OSGi resolver ignores it — it never affects package
   * wiring.
   */
  public static final String EMBED_CAPABILITY_NAMESPACE = "io.nxmatic.rke2lab.embed";

  /** The {@code Fragment-Host} header — present iff the bundle is a fragment. */
  public static final String FRAGMENT_HOST = "Fragment-Host";

  private BundleManifest() {}

  /**
   * The host bundle's symbolic name a fragment attaches to (the value of {@code Fragment-Host},
   * stripped of any attributes), or {@code null} if {@code fragmentHostHeader} is absent — i.e. the
   * bundle is not a fragment. A fragment is NEVER started: it has no own lifecycle, it merges into
   * its host at resolution (OSGi Core §3.14). Read so a test that SELECTS a fixture fragment by
   * capability can install + resolve its host without naming the host: the fragment declares which
   * host it attaches to, the installer reads it.
   */
  public static String fragmentHost(String fragmentHostHeader) {
    return fragmentHostHeader == null ? null : fragmentHostHeader.split(";", 2)[0].trim();
  }

  /**
   * The ONE rule for "is this an embeddable bundle": its {@code Provide-Capability} header names
   * {@link #EMBED_CAPABILITY_NAMESPACE}. Both discovery sources route their verdict through here,
   * so the marker is checked in exactly one place.
   */
  public static boolean declaresEmbed(String provideCapabilityHeader) {
    return provideCapabilityHeader != null
        && provideCapabilityHeader.contains(EMBED_CAPABILITY_NAMESPACE);
  }

  /**
   * Read {@code header} from a jar file or an exploded {@code target/classes} dir, or {@code null}.
   */
  public static String readHeader(Path bundleLocation, String header) throws IOException {
    if (Files.isDirectory(bundleLocation)) {
      try (InputStream in = Files.newInputStream(bundleLocation.resolve("META-INF/MANIFEST.MF"))) {
        return new Manifest(in).getMainAttributes().getValue(header);
      }
    }
    try (JarFile jar = new JarFile(bundleLocation.toFile())) {
      final Manifest manifest = jar.getManifest();
      return manifest == null ? null : manifest.getMainAttributes().getValue(header);
    }
  }

  /**
   * Read {@code header} from a jar STREAM's manifest (an embedded bundle resource), or {@code
   * null}.
   */
  public static String readHeader(InputStream jarStream, String header) throws IOException {
    try (JarInputStream jar = new JarInputStream(jarStream)) {
      final Manifest manifest = jar.getManifest();
      return manifest == null ? null : manifest.getMainAttributes().getValue(header);
    }
  }

  /**
   * Turn an {@code Import-Package} header into system-bundle export clauses: package name kept, an
   * explicit version range narrowed to its lower bound (so the importer's range is satisfied),
   * every other directive/attribute dropped. The bundle's OWN exports are not mirrored — only what
   * it imports needs wiring for it to resolve against a flat classpath.
   */
  public static Set<String> mirrorImportsAsExports(String importPackage) {
    if (importPackage == null || importPackage.isBlank()) {
      return Set.of();
    }
    final Set<String> exports = new LinkedHashSet<>();
    for (String clause : splitClauses(importPackage)) {
      exports.add(importClauseToExport(clause));
    }
    return exports;
  }

  /** All package names in an {@code Export-Package}/{@code Import-Package} header. */
  public static Set<String> packageNames(String header) {
    if (header == null || header.isBlank()) {
      return Set.of();
    }
    final Set<String> names = new LinkedHashSet<>();
    for (String clause : splitClauses(header)) {
      names.add(packageName(clause));
    }
    return names;
  }

  /**
   * Bare package name of an export/import clause ({@code foo.bar;version=1.0} → {@code foo.bar}).
   */
  public static String packageName(String clause) {
    return clause.split(";", 2)[0].trim();
  }

  private static String importClauseToExport(String clause) {
    final String pkg = clause.split(";", 2)[0].trim();
    for (String part : clause.split(";")) {
      final String p = part.trim();
      if (p.startsWith("version=")) {
        final String raw = p.substring("version=".length()).replace("\"", "");
        final String lower =
            raw.startsWith("[") || raw.startsWith("(") ? raw.substring(1).split(",")[0] : raw;
        return pkg + ";version=" + lower.trim();
      }
    }
    return pkg;
  }

  /** Split an OSGi header value on commas that are NOT inside a quoted version range. */
  public static List<String> splitClauses(String header) {
    final List<String> clauses = new ArrayList<>();
    int depth = 0;
    int start = 0;
    for (int i = 0; i < header.length(); i++) {
      final char c = header.charAt(i);
      if (c == '"') {
        depth ^= 1;
      } else if (c == ',' && depth == 0) {
        clauses.add(header.substring(start, i));
        start = i + 1;
      }
    }
    clauses.add(header.substring(start));
    return clauses;
  }
}
