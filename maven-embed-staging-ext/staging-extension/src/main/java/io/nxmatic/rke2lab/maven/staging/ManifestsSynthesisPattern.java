package io.nxmatic.rke2lab.maven.staging;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

/**
 * The build-time shape check of a manifests synthesis phase (the {@code SYNTHESIS_PATTERN} law),
 * two increments in one pass over a scanned surface (a bundle jar or the exec's {@code
 * target/classes}, the dual-surface scan {@link RealmBoundary} also uses).
 *
 * <ul>
 *   <li><b>Nature (A):</b> a class that {@code implements Phase} declares its nature — {@code
 *       Phase.Execution}. The nature is a nested TYPE (not a returned enum), visible in the
 *       interface list; a bare {@code Phase} with no nature is out of spec.
 *   <li><b>Read-face (C) — the one-source-of-truth invariant:</b> at the site where an owner
 *       constructs a phase, no constructor argument may be a bare read of a produced slot off the
 *       accumulator. A phase reads such a slot through a {@code Supplier} read-face (a method-ref,
 *       compiled to {@code invokedynamic}), never a copied reference — because a copy silently
 *       drifts from its source when the system evolves.
 * </ul>
 *
 * <p><b>How C decides, with no name heuristic.</b> The <i>accumulator</i> is discovered
 * structurally: a type whose instance field is written ({@code putfield}) from a method of ANOTHER
 * class — the signature of a mutable slot a sink writes into (the pipeline's {@code StateBuilder} /
 * local {@code State}), never an immutable inputs record (its fields are written only in its own
 * constructor). At each {@code new Phase(...)} the {@link SourceInterpreter} traces the producing
 * instruction of every argument; an argument produced by an {@code invokevirtual}/{@code
 * invokeinterface} whose owner IS an accumulator type is a bare slot read — the violation. A {@code
 * Supplier} read-face is an {@code invokedynamic} (not flagged); a derived scalar ({@code
 * builder.bootstrap().deployment().timestamp()}) has its argument produced by {@code timestamp()}
 * on a non-accumulator type (not flagged) — the immediate producer is what the interpreter reports,
 * so the intermediate accumulator read is invisible, exactly as intended.
 */
final class ManifestsSynthesisPattern {

  private static final String PHASE = "io/nxmatic/rke2lab/manifests/internal/synthesis/Phase";
  private static final String EXECUTION =
      "io/nxmatic/rke2lab/manifests/internal/synthesis/Phase$Execution";
  private static final String INIT = "<init>";

  private ManifestsSynthesisPattern() {}

  /** Nature (A) + read-face (C) violations across one scanned surface. */
  static List<String> violations(List<ResolvedBundle.ClassEntry> classes) {
    final List<ClassNode> nodes = new ArrayList<>();
    for (ResolvedBundle.ClassEntry entry : classes) {
      final ClassNode node = new ClassNode();
      new ClassReader(entry.bytes()).accept(node, ClassReader.SKIP_DEBUG);
      nodes.add(node);
    }

    final Set<String> phases = phaseTypes(nodes);
    final Set<String> accumulators = accumulatorTypes(nodes);

    final List<String> lines = new ArrayList<>();
    for (ClassNode node : nodes) {
      natureViolations(node, lines);
      readFaceViolations(node, phases, accumulators, lines);
    }
    return lines;
  }

  // ---- Nature (A) ----------------------------------------------------------------------------

  private static void natureViolations(ClassNode node, List<String> lines) {
    if (isContract(node.name) || !isPhase(node)) {
      return;
    }
    final int natures = natureCount(node);
    final String simple = simpleName(node.name);
    if (natures == 0) {
      lines.add(simple + " implements Phase with no nature (Execution)");
    }
  }

  // ---- Read-face (C) -------------------------------------------------------------------------

  /**
   * Types whose classes construct phases carry the accumulator reads: for each method, each {@code
   * new Phase(...)} is traced and any argument produced by a bare accumulator-slot read is flagged.
   */
  private static void readFaceViolations(
      ClassNode owner, Set<String> phases, Set<String> accumulators, List<String> lines) {
    for (MethodNode method : owner.methods) {
      if (method.instructions.size() == 0 || !constructsAPhase(method, phases)) {
        continue;
      }
      final Frame<SourceValue>[] frames = analyze(owner.name, method);
      if (frames == null) {
        continue;
      }
      final AbstractInsnNode[] insns = method.instructions.toArray();
      for (int i = 0; i < insns.length; i++) {
        if (!(insns[i] instanceof MethodInsnNode call)
            || call.getOpcode() != Opcodes.INVOKESPECIAL
            || !INIT.equals(call.name)
            || !phases.contains(call.owner)) {
          continue;
        }
        final Frame<SourceValue> frame = frames[i];
        if (frame == null) {
          continue; // dead code — never reached.
        }
        flagBareAccumulatorArgs(call, frame, accumulators, simpleName(owner.name), lines);
      }
    }
  }

  /**
   * For each argument of the phase constructor {@code call}, flag it when its single producing
   * instruction is an {@code invokevirtual}/{@code invokeinterface} whose owner is an accumulator
   * type — a bare read of a produced slot, which must instead be a {@code Supplier} read-face.
   */
  private static void flagBareAccumulatorArgs(
      MethodInsnNode call,
      Frame<SourceValue> frame,
      Set<String> accumulators,
      String ownerSimple,
      List<String> lines) {
    final Type[] argTypes = Type.getArgumentTypes(call.desc);
    int slot = frame.getStackSize();
    for (int a = argTypes.length - 1; a >= 0; a--) {
      slot -= argTypes[a].getSize();
      if (slot < 0) {
        return; // defensive: stack shape unexpected.
      }
      final SourceValue value = frame.getStack(slot);
      if (value.insns.size() != 1) {
        continue; // merged from branches — not a single bare read.
      }
      final AbstractInsnNode producer = value.insns.iterator().next();
      if (producer instanceof MethodInsnNode read
          && (read.getOpcode() == Opcodes.INVOKEVIRTUAL
              || read.getOpcode() == Opcodes.INVOKEINTERFACE)
          && accumulators.contains(read.owner)) {
        lines.add(
            ownerSimple
                + " constructs "
                + simpleName(call.owner)
                + " with a bare accumulator read "
                + simpleName(read.owner)
                + "."
                + read.name
                + "() (use a Supplier read-face)");
      }
    }
  }

  private static boolean constructsAPhase(MethodNode method, Set<String> phases) {
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (insn instanceof MethodInsnNode call
          && call.getOpcode() == Opcodes.INVOKESPECIAL
          && INIT.equals(call.name)
          && phases.contains(call.owner)) {
        return true;
      }
    }
    return false;
  }

  private static Frame<SourceValue>[] analyze(String ownerInternalName, MethodNode method) {
    try {
      return new Analyzer<>(new SourceInterpreter()).analyze(ownerInternalName, method);
    } catch (AnalyzerException ex) {
      return null; // unanalyzable method — skip rather than fail the build on a tooling limit.
    }
  }

  // ---- Type sets over the surface ------------------------------------------------------------

  private static Set<String> phaseTypes(List<ClassNode> nodes) {
    final Set<String> phases = new HashSet<>();
    for (ClassNode node : nodes) {
      if (!isContract(node.name) && isPhase(node)) {
        phases.add(node.name);
      }
    }
    return phases;
  }

  /**
   * A type is an accumulator iff one of its instance fields is written ({@code putfield}) from a
   * method of ANOTHER class — a mutable slot a sink writes into. An immutable inputs record writes
   * its fields only in its own constructor (same class), so it never qualifies.
   */
  private static Set<String> accumulatorTypes(List<ClassNode> nodes) {
    final Set<String> accumulators = new HashSet<>();
    for (ClassNode node : nodes) {
      for (MethodNode method : node.methods) {
        for (AbstractInsnNode insn : method.instructions.toArray()) {
          if (insn instanceof FieldInsnNode field
              && field.getOpcode() == Opcodes.PUTFIELD
              && !field.owner.equals(node.name)) {
            accumulators.add(field.owner);
          }
        }
      }
    }
    return accumulators;
  }

  // ---- Shared predicates ---------------------------------------------------------------------

  private static boolean isPhase(ClassNode node) {
    for (String iface : node.interfaces) {
      if (PHASE.equals(iface) || EXECUTION.equals(iface)) {
        return true;
      }
    }
    return false;
  }

  private static int natureCount(ClassNode node) {
    int natures = 0;
    for (String iface : node.interfaces) {
      if (EXECUTION.equals(iface)) {
        natures++;
      }
    }
    return natures;
  }

  /**
   * The {@code Phase} contract itself and its nested nature interfaces extend {@code Phase} (so
   * they carry it in their own interface list) but are the DEFINITION, not phases — skipped by
   * name.
   */
  private static boolean isContract(String internalName) {
    return internalName.startsWith(PHASE);
  }

  private static String simpleName(String internalName) {
    final int slash = internalName.lastIndexOf('/');
    return slash < 0 ? internalName : internalName.substring(slash + 1);
  }
}
