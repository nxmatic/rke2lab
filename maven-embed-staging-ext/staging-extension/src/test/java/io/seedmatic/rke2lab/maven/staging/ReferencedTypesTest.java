package io.seedmatic.rke2lab.maven.staging;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class ReferencedTypesTest {

  @Test
  void collectsATypeReferencedOnlyInAMethodBody() {
    // A class io.seedmatic.rke2lab.host.Policy whose from() invokes
    // io.seedmatic.rke2lab.doctor.records.Severity.parse(String) — the real drift shape.
    final ClassWriter cw = new ClassWriter(0);
    cw.visit(
        Opcodes.V17,
        Opcodes.ACC_PUBLIC,
        "io/seedmatic/rke2lab/host/Policy",
        null,
        "java/lang/Object",
        null);
    final MethodVisitor mv =
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "from", "()V", null, null);
    mv.visitCode();
    mv.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        "io/seedmatic/rke2lab/doctor/records/Severity",
        "parse",
        "(Ljava/lang/String;)Ljava/util/Optional;",
        false);
    mv.visitInsn(Opcodes.POP);
    mv.visitInsn(Opcodes.RETURN);
    mv.visitMaxs(1, 0);
    mv.visitEnd();
    cw.visitEnd();

    final Set<String> pkgs = ReferencedTypes.in(cw.toByteArray());
    assertTrue(
        pkgs.contains("io.seedmatic.rke2lab.doctor.records"),
        "a type used only in a method body must be collected (no SKIP_CODE)");
  }

  @Test
  void filtersOutForeignAndJdkPackages() {
    final ClassWriter cw = new ClassWriter(0);
    cw.visit(
        Opcodes.V17,
        Opcodes.ACC_PUBLIC,
        "io/seedmatic/rke2lab/host/Plain",
        null,
        "java/lang/Object",
        null);
    cw.visitField(Opcodes.ACC_PRIVATE, "log", "Lorg/slf4j/Logger;", null, null).visitEnd();
    cw.visitEnd();
    final Set<String> pkgs = ReferencedTypes.in(cw.toByteArray());
    assertFalse(pkgs.contains("org.slf4j"), "foreign packages are out of jurisdiction");
    assertFalse(pkgs.stream().anyMatch(p -> p.startsWith("java.")), "JDK is not ours");
  }
}
