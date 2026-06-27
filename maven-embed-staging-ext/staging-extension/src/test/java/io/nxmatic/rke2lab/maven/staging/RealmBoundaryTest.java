package io.nxmatic.rke2lab.maven.staging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class RealmBoundaryTest {

  private static final String FORBIDDEN = "io.nxmatic.rke2lab.doctor.records";

  private static byte[] classReferencing(String ownerInternal) {
    final ClassWriter cw = new ClassWriter(0);
    cw.visit(
        Opcodes.V17,
        Opcodes.ACC_PUBLIC,
        "io/nxmatic/rke2lab/host/Policy",
        null,
        "java/lang/Object",
        null);
    final MethodVisitor mv =
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "from", "()V", null, null);
    mv.visitCode();
    mv.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        ownerInternal,
        "parse",
        "(Ljava/lang/String;)Ljava/util/Optional;",
        false);
    mv.visitInsn(Opcodes.POP);
    mv.visitInsn(Opcodes.RETURN);
    mv.visitMaxs(1, 0);
    mv.visitEnd();
    cw.visitEnd();
    return cw.toByteArray();
  }

  @Test
  void aFlatClassReferencingAForbiddenPackageLeaks() {
    final RealmBoundary gate =
        new RealmBoundary("flat", Set.of(FORBIDDEN), Set.of("io.nxmatic.rke2lab.host"));
    final List<String> v =
        gate.violations(
            "io/nxmatic/rke2lab/host/Policy",
            classReferencing("io/nxmatic/rke2lab/doctor/records/Severity"));
    assertEquals(1, v.size(), "one leak expected");
    assertTrue(
        v.get(0).contains("flat") && v.get(0).contains("Policy") && v.get(0).contains(FORBIDDEN),
        "line carries realm + type + forbidden package");
  }

  @Test
  void aClassReferencingOnlyItsOwnPackageIsClean() {
    final RealmBoundary gate =
        new RealmBoundary("flat", Set.of(FORBIDDEN), Set.of("io.nxmatic.rke2lab.host"));
    final List<String> v =
        gate.violations(
            "io/nxmatic/rke2lab/host/Policy", classReferencing("io/nxmatic/rke2lab/host/Helper"));
    assertTrue(v.isEmpty(), "a reference within the visible set is not a leak");
  }
}
