package io.nxmatic.rke2lab.maven.staging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The contract of {@link InstanceDiscipline} — an exported type must publish no {@code public
 * static} behaviour helper. Each case synthesises a class with one method of a given shape, packs
 * it, and asserts what {@link InstanceDiscipline#violations()} reports: behaviour flagged,
 * factories and {@code @Exempt} methods cleared. Bytecode metadata only (ASM), like its twins.
 */
class InstanceDisciplineTest {

  private static final String PKG = "ex";
  private static final String EXEMPT = "io/nxmatic/rke2lab/domain/annotations/Exempt";
  private static final String GATE = "io/nxmatic/rke2lab/domain/annotations/Gate";

  @Test
  void aPublicStaticBehaviourMethodIsAViolation(@TempDir File dir) throws IOException {
    final File jar = jar(dir, klass("Helper", m -> staticMethod(m, "doWork", "()V", null)));
    assertEquals(List.of("Helper#doWork"), discipline(jar).violations());
  }

  @Test
  void anInstanceMethodIsNotAViolation(@TempDir File dir) throws IOException {
    final File jar = jar(dir, klass("Svc", m -> instanceMethod(m, "doWork")));
    assertTrue(discipline(jar).violations().isEmpty(), "instance methods are the discipline");
  }

  @Test
  void aFactoryMethodIsExemptByTheRule(@TempDir File dir) throws IOException {
    // of(...) returning anything is a construction verb — part of the rule, no annotation needed.
    final File jar = jar(dir, klass("Widget", m -> staticMethod(m, "of", "()Lex/Widget;", null)));
    assertTrue(discipline(jar).violations().isEmpty(), "factories are construction, not behaviour");
  }

  @Test
  void aMethodReturningItsOwnTypeIsExemptByShape(@TempDir File dir) throws IOException {
    final File jar =
        jar(dir, klass("Widget", m -> staticMethod(m, "derive", "()Lex/Widget;", null)));
    assertTrue(discipline(jar).violations().isEmpty(), "returns self ⇒ fluent factory");
  }

  @Test
  void anExemptMethodIsSkipped(@TempDir File dir) throws IOException {
    final File jar =
        jar(dir, klass("Helper", m -> staticMethod(m, "doWork", "()V", "INSTANCE_DISCIPLINE")));
    assertTrue(
        discipline(jar).violations().isEmpty(),
        "@Exempt(INSTANCE_DISCIPLINE) removes the element from the gate");
  }

  @Test
  void anExemptForAnotherGateStillFlags(@TempDir File dir) throws IOException {
    final File jar =
        jar(dir, klass("Helper", m -> staticMethod(m, "doWork", "()V", "SPEC_COVERAGE")));
    assertEquals(
        List.of("Helper#doWork"),
        discipline(jar).violations(),
        "an exemption is per-gate; SPEC_COVERAGE does not excuse INSTANCE_DISCIPLINE");
  }

  private static InstanceDiscipline discipline(File jar) {
    return ResolvedBundle.read("g", "a", "1", jar).instanceDiscipline();
  }

  private record ClassNode(String binaryName, byte[] bytes) {}

  private static ClassNode klass(String simple, java.util.function.Consumer<ClassWriter> body) {
    final ClassWriter cw = new ClassWriter(0);
    cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, PKG + "/" + simple, null, "java/lang/Object", null);
    body.accept(cw);
    cw.visitEnd();
    return new ClassNode(PKG + "/" + simple, cw.toByteArray());
  }

  /**
   * A public static method; if {@code exemptGate} is non-null, carrying {@code @Exempt(thatGate)}.
   */
  private static void staticMethod(
      ClassWriter cw, String name, String descriptor, String exemptGate) {
    final MethodVisitor mv =
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, descriptor, null, null);
    if (exemptGate != null) {
      final AnnotationVisitor av = mv.visitAnnotation("L" + EXEMPT + ";", false);
      av.visitEnum("value", "L" + GATE + ";", exemptGate);
      av.visit("reason", "test");
      av.visitEnd();
    }
    mv.visitEnd();
  }

  private static void instanceMethod(ClassWriter cw, String name) {
    cw.visitMethod(Opcodes.ACC_PUBLIC, name, "()V", null, null).visitEnd();
  }

  private static File jar(File dir, ClassNode node) throws IOException {
    final Manifest manifest = new Manifest();
    final Attributes main = manifest.getMainAttributes();
    main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
    main.putValue("Bundle-SymbolicName", "ex.domain");
    main.putValue("Export-Package", PKG);
    main.putValue("Provide-Capability", "io.nxmatic.rke2lab.embed; type=model");

    final File jar = new File(dir, "domain.jar");
    try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar), manifest)) {
      out.putNextEntry(new ZipEntry(node.binaryName() + ".class"));
      out.write(node.bytes());
      out.closeEntry();
    }
    return jar;
  }
}
