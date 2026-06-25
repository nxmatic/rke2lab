package io.nxmatic.rke2lab.maven.staging;

import io.nxmatic.rke2lab.osgi.bnd.Clause;
import io.nxmatic.rke2lab.osgi.bnd.EmbedCapability;
import io.nxmatic.rke2lab.osgi.bnd.OsgiHeader;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

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
    File file,
    String symbolicName,
    EmbedCapability embed,
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
          file,
          null,
          null,
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
          file,
          bsn,
          EmbedCapability.of(provide),
          imports,
          exports,
          launcher);
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot read jar of " + groupId + ":" + artifactId, ex);
    }
  }

  /** The {@code groupId:artifactId} key — the pom-side identity the shade/staging lists name. */
  public String ga() {
    return groupId + ":" + artifactId;
  }

  /** The stable staged file name BootPlanner looks the jar up under — version-independent. */
  public String stagedFileName() {
    return artifactId + ".jar";
  }

  /** Whether this jar is a real OSGi bundle (declares a {@code Bundle-SymbolicName}). */
  public boolean isBundle() {
    return symbolicName != null;
  }

  /**
   * The purity check OF this bundle — only meaningful when it is a {@code type=record} carrier. The
   * check is an instance reached from its subject (object-graph-navigability), not a static helper.
   */
  public RecordPurity recordPurity() {
    return new RecordPurity(this);
  }

  /** Strip the {@code ;singleton:=true} and other attributes a BSN header may carry. */
  private static String bareSymbolicName(String header) {
    return header == null ? null : Clause.parse(header).name();
  }
}
