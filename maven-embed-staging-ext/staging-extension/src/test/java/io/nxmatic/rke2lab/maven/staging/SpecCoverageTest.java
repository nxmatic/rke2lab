package io.nxmatic.rke2lab.maven.staging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
 * The contract of the spec-coverage guard — the law that keeps the specs in step with the code.
 * Each case synthesises a {@code .class} carrying exactly the metadata the guard reads (exported
 * package + {@code @Transitional} / {@code @SpecGoverned} annotations), packs it into a jar with an
 * {@code Export-Package} manifest, writes a tiny {@code docs/} corpus, and asserts what {@link
 * SpecCoverage#violations()} reports. Bytecode metadata only (ASM) — never a loadable class, the
 * same reason as its twin {@link ContractPurityTest}.
 */
class SpecCoverageTest {

  // Under OUR root: the gates govern only io.nxmatic.rke2lab.* (a carrier's foreign exports are out
  // of jurisdiction — see ResolvedBundle#ourExportedPackages). PKG is the dotted Export-Package
  // value;
  // PATH is its binary form for the synthesised .class entries.
  private static final String PKG = "io.nxmatic.rke2lab.ex";
  private static final String PATH = PKG.replace('.', '/');
  private static final String TRANSITIONAL = "io/nxmatic/rke2lab/domain/annotations/Transitional";

  @Test
  void aTypeNamedInTheSpecsIsCovered(@TempDir File dir) throws IOException {
    final Path docs = docs(dir, "the Widget is the central type of this domain.");
    final File jar = jar(dir, plainClass(PATH + "/Widget"));
    assertTrue(coverage(jar, docs).violations().isEmpty(), "Widget is named in a spec");
  }

  @Test
  void aTypeAbsentFromTheSpecsIsDrift(@TempDir File dir) throws IOException {
    final Path docs = docs(dir, "this domain documents nothing relevant.");
    final File jar = jar(dir, plainClass(PATH + "/Undocumented"));
    assertEquals(List.of("Undocumented"), coverage(jar, docs).violations());
  }

  @Test
  void aTransitionalTypeIsNotDriftEvenWhenAbsent(@TempDir File dir) throws IOException {
    final Path docs = docs(dir, "the successor EfficacyReport is described here.");
    final File jar = jar(dir, annotatedClass(PATH + "/OldType", TRANSITIONAL));
    assertTrue(
        coverage(jar, docs).violations().isEmpty(),
        "an absent type marked @Transitional is in transition, not drift");
  }

  @Test
  void aTypeInANonExportedPackageIsSkipped(@TempDir File dir) throws IOException {
    final Path docs = docs(dir, "only PKG is exported and documented: Widget.");
    final File jar =
        jar(
            dir,
            PKG,
            plainClass(PATH + "/Widget"),
            plainClass("hidden/Undocumented")); // not on the exported surface
    assertTrue(
        coverage(jar, docs).violations().isEmpty(),
        "a non-exported type is not the published surface");
  }

  private static SpecCoverage coverage(File jar, Path docs) {
    return ResolvedBundle.read("g", "a", "1", jar).specCoverage(docs);
  }

  private record ClassNode(String binaryName, byte[] bytes) {}

  private static ClassNode plainClass(String binaryName) {
    final ClassWriter cw = new ClassWriter(0);
    cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, binaryName, null, "java/lang/Object", null);
    cw.visitEnd();
    return new ClassNode(binaryName, cw.toByteArray());
  }

  /** A class carrying the given annotation descriptor (retention CLASS → visible=false). */
  private static ClassNode annotatedClass(String binaryName, String annotationBinary) {
    final ClassWriter cw = new ClassWriter(0);
    cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, binaryName, null, "java/lang/Object", null);
    final AnnotationVisitor av = cw.visitAnnotation("L" + annotationBinary + ";", false);
    av.visitEnd();
    cw.visitEnd();
    return new ClassNode(binaryName, cw.toByteArray());
  }

  private static Path docs(File dir, String body) throws IOException {
    final Path docsDir = dir.toPath().resolve("docs");
    Files.createDirectories(docsDir);
    Files.writeString(docsDir.resolve("spec.adoc"), body, StandardCharsets.UTF_8);
    return docsDir;
  }

  private static File jar(File dir, ClassNode... nodes) throws IOException {
    return jar(dir, PKG, nodes);
  }

  private static File jar(File dir, String exportedPackage, ClassNode... nodes) throws IOException {
    final Manifest manifest = new Manifest();
    final Attributes main = manifest.getMainAttributes();
    main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
    main.putValue("Bundle-SymbolicName", "ex.domain");
    main.putValue("Export-Package", exportedPackage);
    main.putValue("Provide-Capability", "io.nxmatic.rke2lab.embed; type=model");

    final File jar = new File(dir, "domain.jar");
    try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar), manifest)) {
      for (ClassNode node : nodes) {
        out.putNextEntry(new ZipEntry(node.binaryName() + ".class"));
        out.write(node.bytes());
        out.closeEntry();
      }
    }
    return jar;
  }
}
