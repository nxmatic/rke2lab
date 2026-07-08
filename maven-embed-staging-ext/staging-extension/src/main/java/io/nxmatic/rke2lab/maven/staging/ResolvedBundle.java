package io.nxmatic.rke2lab.maven.staging;

import io.nxmatic.rke2lab.osgi.bnd.Clause;
import io.nxmatic.rke2lab.osgi.bnd.EmbedCapability;
import io.nxmatic.rke2lab.osgi.bnd.OsgiHeader;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

/**
 * One resolved dependency jar of an exec-module, read through the OSGi lens at BUILD time — the
 * same headers the runtime boot model reads, parsed with the same {@code bnd-read} primitives, so
 * the stage-vs-flat decision is taken from what the jar DECLARES, never from a hand-kept list. A
 * jar with no {@code Bundle-SymbolicName} and no embed capability is a flat library; the rest carry
 * enough to place themselves.
 */
public record ResolvedBundle(
    String groupId,
    String artifactId,
    String version,
    Optional<File> file,
    Optional<String> symbolicName,
    Optional<EmbedCapability> embed,
    OsgiHeader imports,
    OsgiHeader exports,
    boolean launcher) {

  /**
   * The service file a launchable OSGi framework declares — how the runtime finds it, so we do too.
   */
  private static final String FRAMEWORK_FACTORY_SERVICE =
      "META-INF/services/org.osgi.framework.launch.FrameworkFactory";

  /**
   * Read the resolved jar through the OSGi lens. An artifact without a resolved file (or an
   * unreadable jar) is not a bundle we can place — reported as a flat jar with empty headers.
   */
  public static ResolvedBundle read(String groupId, String artifactId, String version, File file) {
    if (file == null || !file.isFile()) {
      return new ResolvedBundle(
          groupId,
          artifactId,
          version,
          Optional.ofNullable(file),
          Optional.empty(),
          Optional.empty(),
          OsgiHeader.parse(null),
          OsgiHeader.parse(null),
          false);
    }
    try (JarFile jar = new JarFile(file)) {
      final Manifest manifest = jar.getManifest();
      final Attributes main = manifest == null ? new Attributes() : manifest.getMainAttributes();
      final String bsn = bareSymbolicName(main.getValue("Bundle-SymbolicName"));
      final OsgiHeader provide = OsgiHeader.parse(main.getValue("Provide-Capability"));
      final OsgiHeader imports = OsgiHeader.parse(main.getValue("Import-Package"));
      final OsgiHeader exports = OsgiHeader.parse(main.getValue("Export-Package"));
      final boolean launcher = jar.getEntry(FRAMEWORK_FACTORY_SERVICE) != null;
      return new ResolvedBundle(
          groupId,
          artifactId,
          version,
          Optional.of(file),
          Optional.ofNullable(bsn),
          Optional.ofNullable(EmbedCapability.of(provide)),
          imports,
          exports,
          launcher);
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot read jar of " + groupId + ":" + artifactId, ex);
    }
  }

  /**
   * Our package root. The staging gates (record-purity, spec-coverage, instance-discipline) judge
   * only OUR code: a type under {@code io.nxmatic.rke2lab.*}. A carrier whose job is to re-export a
   * third-party closure ({@code manifests-cdk8s} exporting {@code org.cdk8s} / {@code
   * software.constructs}) is exporting packages that are not ours to spec, purify, or discipline —
   * the same reason {@code -noimportjava} does not govern the JDK. The gates filter exported
   * packages through {@link #isOurs(String)} so foreign exports are out of their jurisdiction.
   */
  public static final String OUR_ROOT = "io.nxmatic.rke2lab";

  /** The {@code groupId:artifactId} key — the pom-side identity the shade/staging lists name. */
  public String ga() {
    return groupId + ":" + artifactId;
  }

  /**
   * The exported packages UNDER our root — the published surface the gates govern. A carrier's
   * re-exported third-party packages ({@code org.cdk8s}, …) are excluded: not ours to judge.
   */
  public Set<String> ourExportedPackages() {
    return exports.names().stream()
        .filter(ResolvedBundle::isOurs)
        .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
  }

  /**
   * Whether {@code packageName} is under our package root — i.e. our code, the gates' jurisdiction.
   */
  public static boolean isOurs(String packageName) {
    return packageName.equals(OUR_ROOT) || packageName.startsWith(OUR_ROOT + ".");
  }

  /** The stable staged file name BootPlanner looks the jar up under — version-independent. */
  public String stagedFileName() {
    return artifactId + ".jar";
  }

  /** Whether this jar is a real OSGi bundle (declares a {@code Bundle-SymbolicName}). */
  public boolean isBundle() {
    return symbolicName.isPresent();
  }

  /**
   * The purity check OF this bundle — only meaningful when it is a {@code type=record} carrier. The
   * check is an instance reached from its subject (object-graph-navigability), not a static helper.
   */
  public RecordPurity recordPurity() {
    return new RecordPurity(this);
  }

  /**
   * The spec-coverage check OF this bundle against {@code docsDir} — every exported type must be
   * named in a spec or {@code @Transitional}. Reports the violations only; how they are reported is
   * decided by {@link #governance()}. An instance reached from its subject, like {@link
   * #recordPurity()}.
   */
  public SpecCoverage specCoverage(java.nio.file.Path docsDir) {
    return new SpecCoverage(this, docsDir);
  }

  /**
   * The instance-discipline check OF this bundle — exported types should not publish {@code public
   * static} behaviour helpers (factories and constants exempt). Reports the violations only; the
   * level is {@link #governance()}'s call. An instance reached from its subject, like the twins.
   */
  public InstanceDiscipline instanceDiscipline() {
    return new InstanceDiscipline(this);
  }

  /**
   * The governance OF this bundle — the {@link EnforcementLevel} each {@link StagingGate} reports
   * it at, read from {@code @GovernedBy} on its package-infos (default {@link
   * EnforcementLevel#ERROR}). An instance reached from its subject, like {@link #recordPurity()} /
   * {@link #specCoverage}.
   */
  public GovernanceReader governance() {
    return new GovernanceReader(this);
  }

  /** One compiled class read from this carrier — its binary name and bytes. */
  public record ClassEntry(String binaryName, byte[] bytes) {}

  /** Every {@code .class} in this carrier's jar (top-level and nested), for body-level scans. */
  public java.util.List<ClassEntry> classEntries() {
    final File jarFile = file().filter(File::isFile).orElse(null);
    if (jarFile == null) {
      return java.util.List.of();
    }
    final java.util.List<ClassEntry> entries = new java.util.ArrayList<>();
    try (JarFile jar = new JarFile(jarFile)) {
      final java.util.Enumeration<java.util.jar.JarEntry> e = jar.entries();
      while (e.hasMoreElements()) {
        final java.util.jar.JarEntry entry = e.nextElement();
        final String name = entry.getName();
        if (!name.endsWith(".class") || name.endsWith("module-info.class")) {
          continue;
        }
        try (var in = jar.getInputStream(entry)) {
          entries.add(new ClassEntry(name.substring(0, name.length() - 6), in.readAllBytes()));
        }
      }
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot read classes of " + ga(), ex);
    }
    return entries;
  }

  /** Every {@code .class} under an exploded classes directory (the exec's own target/classes). */
  public static java.util.List<ClassEntry> classEntriesOf(java.nio.file.Path classesDir) {
    if (classesDir == null || !java.nio.file.Files.isDirectory(classesDir)) {
      return java.util.List.of();
    }
    try (var tree = java.nio.file.Files.walk(classesDir)) {
      final java.util.List<ClassEntry> entries = new java.util.ArrayList<>();
      tree.filter(p -> p.toString().endsWith(".class"))
          .filter(p -> !p.getFileName().toString().equals("module-info.class"))
          .forEach(
              p -> {
                try {
                  final String binary = classesDir.relativize(p).toString().replace('\\', '/');
                  entries.add(
                      new ClassEntry(
                          binary.substring(0, binary.length() - 6),
                          java.nio.file.Files.readAllBytes(p)));
                } catch (IOException ex) {
                  throw new UncheckedIOException("cannot read class " + p, ex);
                }
              });
      return entries;
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot walk classes dir " + classesDir, ex);
    }
  }

  /** Strip the {@code ;singleton:=true} and other attributes a BSN header may carry. */
  private static String bareSymbolicName(String header) {
    return header == null ? null : Clause.parse(header).name();
  }
}
