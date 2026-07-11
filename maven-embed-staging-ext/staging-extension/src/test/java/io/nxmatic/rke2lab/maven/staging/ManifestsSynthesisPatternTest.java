package io.nxmatic.rke2lab.maven.staging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Exercises both increments of {@link ManifestsSynthesisPattern} over hand-forged bytecode: the
 * nature check (A) and the read-face / one-source-of-truth check (C). The classes are written with
 * ASM so a case is exactly one bytecode shape — a bare {@code invokevirtual} on the accumulator
 * (the drift), a read off an immutable inputs holder (fine), a phase with/without a nature.
 */
class ManifestsSynthesisPatternTest {

  private static final String PHASE = "io/nxmatic/rke2lab/manifests/internal/synthesis/Phase";
  private static final String EXECUTION =
      "io/nxmatic/rke2lab/manifests/internal/synthesis/Phase$Execution";
  private static final String ACC =
      "io/nxmatic/rke2lab/x/Builder"; // written externally → accumulator
  private static final String INPUTS = "io/nxmatic/rke2lab/x/Inputs"; // never externally written
  private static final String PHASE_IMPL = "io/nxmatic/rke2lab/x/MyPhase";
  private static final String OWNER = "io/nxmatic/rke2lab/x/Pipeline";

  // ---- forges ---------------------------------------------------------------------------------

  /** An empty class implementing the given interfaces (for the nature check). */
  private static byte[] classImplementing(String internalName, String... interfaces) {
    final ClassWriter cw = new ClassWriter(0);
    cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", interfaces);
    cw.visitEnd();
    return cw.toByteArray();
  }

  /**
   * A holder with a {@code slot()} getter returning Object — the shape both ACC and INPUTS take.
   */
  private static byte[] holder(String internalName) {
    final ClassWriter cw = new ClassWriter(0);
    cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
    cw.visitField(Opcodes.ACC_PUBLIC, "f", "Ljava/lang/Object;", null, null).visitEnd();
    final MethodVisitor mv =
        cw.visitMethod(Opcodes.ACC_PUBLIC, "slot", "()Ljava/lang/Object;", null, null);
    mv.visitCode();
    mv.visitVarInsn(Opcodes.ALOAD, 0);
    mv.visitFieldInsn(Opcodes.GETFIELD, internalName, "f", "Ljava/lang/Object;");
    mv.visitInsn(Opcodes.ARETURN);
    mv.visitMaxs(1, 1);
    mv.visitEnd();
    cw.visitEnd();
    return cw.toByteArray();
  }

  /**
   * A class that does {@code putfield ACC.f} from one of ITS methods (owner != ACC) — the external
   * mutation that marks {@code ACC} as an accumulator (a sink writing into the builder).
   */
  private static byte[] externalWriterOf(String accInternal) {
    final ClassWriter cw = new ClassWriter(0);
    cw.visit(
        Opcodes.V17,
        Opcodes.ACC_PUBLIC,
        "io/nxmatic/rke2lab/x/Sink",
        null,
        "java/lang/Object",
        null);
    final MethodVisitor mv =
        cw.visitMethod(
            Opcodes.ACC_PUBLIC, "write", "(L" + accInternal + ";Ljava/lang/Object;)V", null, null);
    mv.visitCode();
    mv.visitVarInsn(Opcodes.ALOAD, 1);
    mv.visitVarInsn(Opcodes.ALOAD, 2);
    mv.visitFieldInsn(Opcodes.PUTFIELD, accInternal, "f", "Ljava/lang/Object;");
    mv.visitInsn(Opcodes.RETURN);
    mv.visitMaxs(2, 3);
    mv.visitEnd();
    cw.visitEnd();
    return cw.toByteArray();
  }

  /** A phase with a single-arg (Object) constructor. */
  private static byte[] phaseImpl(String... interfaces) {
    final ClassWriter cw = new ClassWriter(0);
    cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, PHASE_IMPL, null, "java/lang/Object", interfaces);
    final MethodVisitor mv =
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/Object;)V", null, null);
    mv.visitCode();
    mv.visitVarInsn(Opcodes.ALOAD, 0);
    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
    mv.visitInsn(Opcodes.RETURN);
    mv.visitMaxs(1, 2);
    mv.visitEnd();
    cw.visitEnd();
    return cw.toByteArray();
  }

  /**
   * An owner method {@code build(holder)} that does {@code new MyPhase(holder.slot())} — a bare
   * read of the argument off {@code holderInternal}. When {@code holderInternal} is the accumulator
   * this is the drift; when it is the immutable inputs holder it is fine.
   */
  private static byte[] ownerConstructing(String holderInternal) {
    final ClassWriter cw = new ClassWriter(0);
    cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, OWNER, null, "java/lang/Object", null);
    final MethodVisitor mv =
        cw.visitMethod(Opcodes.ACC_PUBLIC, "build", "(L" + holderInternal + ";)V", null, null);
    mv.visitCode();
    mv.visitTypeInsn(Opcodes.NEW, PHASE_IMPL);
    mv.visitInsn(Opcodes.DUP);
    mv.visitVarInsn(Opcodes.ALOAD, 1);
    mv.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL, holderInternal, "slot", "()Ljava/lang/Object;", false);
    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, PHASE_IMPL, "<init>", "(Ljava/lang/Object;)V", false);
    mv.visitInsn(Opcodes.POP);
    mv.visitInsn(Opcodes.RETURN);
    mv.visitMaxs(3, 2);
    mv.visitEnd();
    cw.visitEnd();
    return cw.toByteArray();
  }

  private static ResolvedBundle.ClassEntry entry(String binaryName, byte[] bytes) {
    return new ResolvedBundle.ClassEntry(binaryName, bytes);
  }

  // ---- nature (A) -----------------------------------------------------------------------------

  @Test
  void aPhaseWithNoNatureIsFlagged() {
    final List<String> v =
        ManifestsSynthesisPattern.violations(
            List.of(entry(PHASE_IMPL, classImplementing(PHASE_IMPL, PHASE))));
    assertEquals(1, v.size());
    assertTrue(v.get(0).contains("MyPhase") && v.get(0).contains("no nature"), v.toString());
  }

  @Test
  void aPhaseWithOneNatureIsClean() {
    final List<String> v =
        ManifestsSynthesisPattern.violations(
            List.of(entry(PHASE_IMPL, classImplementing(PHASE_IMPL, EXECUTION))));
    assertTrue(v.isEmpty(), v.toString());
  }

  // ---- read-face (C) --------------------------------------------------------------------------

  @Test
  void aBareReadOffTheAccumulatorIsFlagged() {
    final List<ResolvedBundle.ClassEntry> surface = new ArrayList<>();
    surface.add(entry("io/nxmatic/rke2lab/x/Sink", externalWriterOf(ACC))); // marks ACC accumulator
    surface.add(entry(ACC, holder(ACC)));
    surface.add(entry(PHASE_IMPL, phaseImpl(EXECUTION)));
    surface.add(entry(OWNER, ownerConstructing(ACC)));

    final List<String> v = ManifestsSynthesisPattern.violations(surface);
    assertEquals(1, v.size(), v.toString());
    assertTrue(
        v.get(0).contains("Pipeline")
            && v.get(0).contains("MyPhase")
            && v.get(0).contains("Supplier read-face"),
        v.toString());
  }

  @Test
  void aBareReadOffAnImmutableInputsHolderIsClean() {
    // INPUTS is never written from another class, so it is NOT an accumulator: reading a slot off
    // it
    // and forwarding it is an injected collaborator, not a copied produced slot.
    final List<ResolvedBundle.ClassEntry> surface = new ArrayList<>();
    surface.add(
        entry("io/nxmatic/rke2lab/x/Sink", externalWriterOf(ACC))); // ACC is the accumulator
    surface.add(entry(ACC, holder(ACC)));
    surface.add(entry(INPUTS, holder(INPUTS)));
    surface.add(entry(PHASE_IMPL, phaseImpl(EXECUTION)));
    surface.add(entry(OWNER, ownerConstructing(INPUTS))); // reads off INPUTS, not the accumulator

    final List<String> v = ManifestsSynthesisPattern.violations(surface);
    assertTrue(v.isEmpty(), v.toString());
  }
}
