package io.nxmatic.rke2lab.maven.staging;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.EnumMap;
import java.util.Enumeration;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Reads a {@link ResolvedBundle}'s declared governance — the {@link EnforcementLevel} each {@link
 * StagingGate} reports this bundle's violations at, from {@code @GovernedBy} (or its repeated
 * container {@code @GovernedByAll}) on the jar's {@code package-info} classes. A gate with no
 * declaration is absent from the map and the caller treats it at the default {@link
 * EnforcementLevel#ERROR} — so <em>governed by default</em> is the standing guarantee.
 *
 * <p>The extension cannot link the annotation module (it is installed before the reactor builds
 * it), so this reads the annotation via ASM and maps the enum-constant names onto the extension's
 * mirror enums — exactly as {@link io.nxmatic.rke2lab.osgi.bnd.EmbedCapability} is a typed view of
 * bnd header strings. An INSTANCE reached from its subject ({@link ResolvedBundle#governance()}),
 * not a static helper — navigable back to the bundle it reads, like the gates themselves.
 */
final class GovernanceReader {

  private static final String GOVERNED_BY = "Lio/nxmatic/rke2lab/domain/annotations/GovernedBy;";
  private static final String GOVERNED_BY_ALL =
      "Lio/nxmatic/rke2lab/domain/annotations/GovernedByAll;";

  private final ResolvedBundle bundle;

  GovernanceReader(ResolvedBundle bundle) {
    this.bundle = bundle;
  }

  /** Parse one package-info's bytes, merging its @GovernedBy poses into {@code levels}. */
  static void readInto(byte[] packageInfo, Map<StagingGate, EnforcementLevel> levels) {
    new ClassReader(packageInfo)
        .accept(
            new GovernanceVisitor(levels),
            ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
  }

  /** The level each gate reports this bundle at; a gate absent from the map defaults to ERROR. */
  Map<StagingGate, EnforcementLevel> levels() {
    final Map<StagingGate, EnforcementLevel> levels = new EnumMap<>(StagingGate.class);
    try (JarFile jar = new JarFile(bundle.file().orElseThrow())) {
      final Enumeration<JarEntry> entries = jar.entries();
      while (entries.hasMoreElements()) {
        final JarEntry entry = entries.nextElement();
        if (!entry.getName().endsWith("package-info.class")) {
          continue;
        }
        try (var in = jar.getInputStream(entry)) {
          new ClassReader(in)
              .accept(
                  new GovernanceVisitor(levels),
                  ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
      }
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot read jar " + bundle.ga(), ex);
    }
    return levels;
  }

  /**
   * The level {@code gate} reports this bundle at, or {@link EnforcementLevel#ERROR} by default.
   */
  EnforcementLevel levelOf(StagingGate gate) {
    return levels().getOrDefault(gate, EnforcementLevel.ERROR);
  }

  /** Collects {@code @GovernedBy} (single) and {@code @GovernedByAll} (container) into the map. */
  private static final class GovernanceVisitor extends ClassVisitor {

    private final Map<StagingGate, EnforcementLevel> levels;

    GovernanceVisitor(Map<StagingGate, EnforcementLevel> levels) {
      super(Opcodes.ASM9);
      this.levels = levels;
    }

    @Override
    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
      if (GOVERNED_BY.equals(desc)) {
        return new GovernedByVisitor(levels);
      }
      if (GOVERNED_BY_ALL.equals(desc)) {
        return new ContainerVisitor(levels);
      }
      return null;
    }
  }

  /** Reads the {@code value} array of {@code @GovernedByAll}, each element a nested @GovernedBy. */
  private static final class ContainerVisitor extends AnnotationVisitor {

    private final Map<StagingGate, EnforcementLevel> levels;

    ContainerVisitor(Map<StagingGate, EnforcementLevel> levels) {
      super(Opcodes.ASM9);
      this.levels = levels;
    }

    @Override
    public AnnotationVisitor visitArray(String name) {
      return "value".equals(name) ? this : null;
    }

    @Override
    public AnnotationVisitor visitAnnotation(String name, String desc) {
      return new GovernedByVisitor(levels); // each array element is a @GovernedBy
    }
  }

  /**
   * Reads one {@code @GovernedBy}: {@code value} (the {@link StagingGate} enum) and {@code level}
   * (the {@link EnforcementLevel} enum, default ERROR when the member is absent). Records the pair
   * when the gate name is known.
   */
  private static final class GovernedByVisitor extends AnnotationVisitor {

    private final Map<StagingGate, EnforcementLevel> levels;
    private StagingGate gate;
    private EnforcementLevel level = EnforcementLevel.ERROR;

    GovernedByVisitor(Map<StagingGate, EnforcementLevel> levels) {
      super(Opcodes.ASM9);
      this.levels = levels;
    }

    @Override
    public void visitEnum(String name, String desc, String value) {
      if ("value".equals(name)) {
        gate = StagingGate.fromName(value);
      } else if ("level".equals(name)) {
        level = EnforcementLevel.fromName(value);
      }
    }

    @Override
    public void visitEnd() {
      if (gate != null) {
        levels.put(gate, level);
      }
    }
  }
}
