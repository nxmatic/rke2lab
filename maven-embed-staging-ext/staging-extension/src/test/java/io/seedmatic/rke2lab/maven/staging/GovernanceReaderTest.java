package io.seedmatic.rke2lab.maven.staging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/**
 * The contract of {@link GovernanceReader} — reading {@code @GovernedBy} / {@code @GovernedByAll}
 * from a bundle's {@code package-info} bytecode into a {@code Map<StagingGate, EnforcementLevel>}.
 * Each case synthesises a {@code package-info.class} carrying the annotation shape under test,
 * packs it into a jar, and asserts the level each gate resolves to (default {@link
 * EnforcementLevel#ERROR} when a gate is unspecified). Bytecode metadata only (ASM), the same
 * reason as {@link SpecCoverageTest}.
 */
class GovernanceReaderTest {

  private static final String PKG = "ex";
  private static final String GOVERNED_BY = "io/seedmatic/rke2lab/domain/annotations/GovernedBy";
  private static final String GOVERNED_BY_ALL =
      "io/seedmatic/rke2lab/domain/annotations/GovernedByAll";
  private static final String GATE = "io/seedmatic/rke2lab/domain/annotations/StagingGate";
  private static final String LEVEL = "io/seedmatic/rke2lab/domain/annotations/EnforcementLevel";

  @Test
  void noAnnotationDefaultsEveryGateToError(@TempDir File dir) throws IOException {
    final File jar = jar(dir, plainPackageInfo());
    final GovernanceReader g = governance(jar);
    assertEquals(EnforcementLevel.ERROR, g.levelOf(StagingGate.SPEC_COVERAGE));
    assertEquals(EnforcementLevel.ERROR, g.levelOf(StagingGate.CONTRACT_PURITY));
  }

  @Test
  void aSinglePoseSetsThatGateLeavingOthersAtDefault(@TempDir File dir) throws IOException {
    final File jar = jar(dir, packageInfo(av -> pose(av, "SPEC_COVERAGE", "WARN")));
    final GovernanceReader g = governance(jar);
    assertEquals(EnforcementLevel.WARN, g.levelOf(StagingGate.SPEC_COVERAGE));
    assertEquals(
        EnforcementLevel.ERROR, g.levelOf(StagingGate.INSTANCE_DISCIPLINE), "unspecified ⇒ ERROR");
  }

  @Test
  void repeatedPosesAreReadFromTheContainer(@TempDir File dir) throws IOException {
    final File jar = jar(dir, containerPackageInfo());
    final GovernanceReader g = governance(jar);
    assertEquals(EnforcementLevel.WARN, g.levelOf(StagingGate.SPEC_COVERAGE));
    assertEquals(EnforcementLevel.IGNORE, g.levelOf(StagingGate.INSTANCE_DISCIPLINE));
    assertEquals(
        EnforcementLevel.ERROR, g.levelOf(StagingGate.CONTRACT_PURITY), "unspecified ⇒ ERROR");
  }

  private static GovernanceReader governance(File jar) {
    return ResolvedBundle.read("g", "a", "1", jar).governance();
  }

  private record ClassNode(String binaryName, byte[] bytes) {}

  /** A bare {@code package-info} with no governance annotation. */
  private static ClassNode plainPackageInfo() {
    return packageInfo(null);
  }

  /**
   * A {@code package-info} carrying a single top-level {@code @GovernedBy} written by {@code body}.
   */
  private static ClassNode packageInfo(java.util.function.Consumer<AnnotationVisitor> body) {
    final ClassWriter cw = packageInfoWriter();
    if (body != null) {
      final AnnotationVisitor av = cw.visitAnnotation("L" + GOVERNED_BY + ";", false);
      body.accept(av);
      av.visitEnd();
    }
    cw.visitEnd();
    return new ClassNode(PKG + "/package-info", cw.toByteArray());
  }

  /**
   * A {@code package-info} carrying two poses, so the compiler-equivalent {@code @GovernedByAll}
   * container shape is exercised: SPEC_COVERAGE=WARN and INSTANCE_DISCIPLINE=IGNORE.
   */
  private static ClassNode containerPackageInfo() {
    final ClassWriter cw = packageInfoWriter();
    final AnnotationVisitor container = cw.visitAnnotation("L" + GOVERNED_BY_ALL + ";", false);
    final AnnotationVisitor array = container.visitArray("value");
    pose(array.visitAnnotation(null, "L" + GOVERNED_BY + ";"), "SPEC_COVERAGE", "WARN");
    pose(array.visitAnnotation(null, "L" + GOVERNED_BY + ";"), "INSTANCE_DISCIPLINE", "IGNORE");
    array.visitEnd();
    container.visitEnd();
    cw.visitEnd();
    return new ClassNode(PKG + "/package-info", cw.toByteArray());
  }

  /** Writes {@code value=gate, level=level} into a {@code @GovernedBy} visitor and closes it. */
  private static void pose(AnnotationVisitor governedBy, String gate, String level) {
    governedBy.visitEnum("value", "L" + GATE + ";", gate);
    governedBy.visitEnum("level", "L" + LEVEL + ";", level);
    governedBy.visitEnd();
  }

  private static ClassWriter packageInfoWriter() {
    final ClassWriter cw = new ClassWriter(0);
    cw.visit(
        Opcodes.V17,
        Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SYNTHETIC,
        PKG + "/package-info",
        null,
        "java/lang/Object",
        null);
    return cw;
  }

  private static File jar(File dir, ClassNode node) throws IOException {
    final Manifest manifest = new Manifest();
    final Attributes main = manifest.getMainAttributes();
    main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
    main.putValue("Bundle-SymbolicName", "ex.domain");
    main.putValue("Export-Package", PKG);
    main.putValue("Provide-Capability", "io.seedmatic.rke2lab.embed; type=model");

    final File jar = new File(dir, "domain.jar");
    try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar), manifest)) {
      out.putNextEntry(new ZipEntry(node.binaryName() + ".class"));
      out.write(node.bytes());
      out.closeEntry();
    }
    return jar;
  }
}
