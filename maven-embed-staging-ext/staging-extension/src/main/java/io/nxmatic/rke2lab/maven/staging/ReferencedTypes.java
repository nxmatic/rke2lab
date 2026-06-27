package io.nxmatic.rke2lab.maven.staging;

import java.util.LinkedHashSet;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

/**
 * Every package under our root ({@link ResolvedBundle#OUR_ROOT}) that a single class REFERENCES —
 * collected from the constant pool the way {@code jdeps} does: super/interfaces, field and method
 * descriptors, generic signatures, AND method bodies (type/field/method instructions, ldc of a
 * Type). Unlike the other gates this reads bodies (no {@code SKIP_CODE}) — the boundary drift it
 * feeds is {@code Severity.parse()}, an invokestatic inside a body. Foreign and JDK packages are
 * dropped: the staging gates judge only our code.
 */
final class ReferencedTypes {

  private ReferencedTypes() {}

  /** The dotted, our-root package names this class references. */
  static Set<String> in(byte[] classfile) {
    final Set<String> packages = new LinkedHashSet<>();
    new ClassReader(classfile)
        .accept(new Collector(packages), ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    return packages;
  }

  /** Add the package of every type named in {@code descriptor} (handles arrays + method types). */
  private static void addDescriptor(Set<String> packages, String descriptor) {
    if (descriptor == null) {
      return;
    }
    if (descriptor.charAt(0) == '(') {
      for (Type arg : Type.getArgumentTypes(descriptor)) {
        addType(packages, arg);
      }
      addType(packages, Type.getReturnType(descriptor));
    } else {
      addType(packages, Type.getType(descriptor));
    }
  }

  private static void addType(Set<String> packages, Type type) {
    Type t = type;
    while (t.getSort() == Type.ARRAY) {
      t = t.getElementType();
    }
    if (t.getSort() == Type.OBJECT) {
      addInternal(packages, t.getInternalName());
    }
  }

  /**
   * Add the package of an internal name (e.g. {@code io/nxmatic/rke2lab/doctor/records/Severity}).
   */
  private static void addInternal(Set<String> packages, String internalName) {
    if (internalName == null) {
      return;
    }
    final int slash = internalName.lastIndexOf('/');
    if (slash < 0) {
      return;
    }
    final String pkg = internalName.substring(0, slash).replace('/', '.');
    if (ResolvedBundle.isOurs(pkg)) {
      packages.add(pkg);
    }
  }

  private static void addSignature(Set<String> packages, String signature) {
    if (signature == null) {
      return;
    }
    new SignatureReader(signature)
        .accept(
            new SignatureVisitor(Opcodes.ASM9) {
              @Override
              public void visitClassType(String name) {
                addInternal(packages, name);
              }
            });
  }

  private static final class Collector extends ClassVisitor {

    private final Set<String> packages;

    Collector(Set<String> packages) {
      super(Opcodes.ASM9);
      this.packages = packages;
    }

    @Override
    public void visit(
        int version,
        int access,
        String name,
        String signature,
        String superName,
        String[] interfaces) {
      addInternal(packages, superName);
      if (interfaces != null) {
        for (String itf : interfaces) {
          addInternal(packages, itf);
        }
      }
      addSignature(packages, signature);
    }

    @Override
    public FieldVisitor visitField(
        int access, String name, String descriptor, String signature, Object value) {
      addDescriptor(packages, descriptor);
      addSignature(packages, signature);
      return null;
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      addDescriptor(packages, descriptor);
      addSignature(packages, signature);
      if (exceptions != null) {
        for (String ex : exceptions) {
          addInternal(packages, ex);
        }
      }
      return new BodyVisitor(packages);
    }
  }

  /** Reads a method body: type/field/method instructions, and ldc of a Type literal. */
  private static final class BodyVisitor extends MethodVisitor {

    private final Set<String> packages;

    BodyVisitor(Set<String> packages) {
      super(Opcodes.ASM9);
      this.packages = packages;
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
      addInternal(packages, type);
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
      addInternal(packages, owner);
      addDescriptor(packages, descriptor);
    }

    @Override
    public void visitMethodInsn(
        int opcode, String owner, String name, String descriptor, boolean isInterface) {
      addInternal(packages, owner);
      addDescriptor(packages, descriptor);
    }

    @Override
    public void visitLdcInsn(Object value) {
      if (value instanceof Type type) {
        addType(packages, type);
      }
    }

    @Override
    public void visitInvokeDynamicInsn(String name, String descriptor, Handle bsm, Object... args) {
      addDescriptor(packages, descriptor);
    }
  }
}
