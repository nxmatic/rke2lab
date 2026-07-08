package io.nxmatic.rke2lab.maven.staging;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * The build-time instance-discipline check OF a {@link ResolvedBundle} — the law behind the
 * project's "prefer instances over static helpers" rule: an exported type should not publish {@code
 * public static} BEHAVIOUR (a {@code XxxHelper.staticMethod()} the call graph cannot navigate to).
 * Pass instances through the call graph instead. The check reads each exported top-level class
 * through ASM (bytecode only, no class linking) and reports every offending {@code public static}
 * method as {@code Type#method}.
 *
 * <p>FACTORIES and pure constants are exempt — they are the endorsed static surface:
 *
 * <ul>
 *   <li>a method whose name is a known factory verb ({@code of}, {@code from*}, {@code parse},
 *       {@code valueOf}, {@code builder}, {@code create}, {@code defaults}, {@code new*}) — the
 *       construction contract, including type-safe enum conversions;
 *   <li>a method that RETURNS its declaring type (a fluent factory by shape, whatever its name);
 *   <li>{@code main}, and the synthetic {@code values}/{@code valueOf} an enum carries;
 *   <li>fields — constants are not behaviour, only methods are inspected.
 * </ul>
 *
 * <p>An INSTANCE reached from its subject ({@link ResolvedBundle#instanceDiscipline()}), not a
 * static helper — the check obeys the very discipline it enforces, navigable back to the bundle it
 * reads, like its twins {@link RecordPurity} / {@link SpecCoverage}.
 */
final class InstanceDiscipline {

  private static final Set<String> FACTORY_NAMES =
      Set.of("of", "parse", "valueOf", "builder", "create", "defaults", "values");

  /**
   * {@code @Exempt}'s descriptor and the {@code value} that names this gate (an ASM enum constant).
   */
  private static final String EXEMPT = "Lio/nxmatic/rke2lab/domain/annotations/Exempt;";

  private static final String EXEMPT_ALL = "Lio/nxmatic/rke2lab/domain/annotations/ExemptAll;";
  private static final String INSTANCE_DISCIPLINE = "INSTANCE_DISCIPLINE";

  private final ResolvedBundle bundle;

  InstanceDiscipline(ResolvedBundle bundle) {
    this.bundle = bundle;
  }

  /** The {@code Type#method} signatures of exported {@code public static} behaviour helpers. */
  List<String> violations() {
    final Set<String> exportedPackages = bundle.ourExportedPackages();
    if (exportedPackages.isEmpty()) {
      return List.of();
    }
    final List<String> violations = new ArrayList<>();
    try (JarFile jar = new JarFile(bundle.file().orElseThrow())) {
      final Enumeration<JarEntry> entries = jar.entries();
      while (entries.hasMoreElements()) {
        final JarEntry entry = entries.nextElement();
        final String name = entry.getName();
        if (!name.endsWith(".class")
            || name.contains("$")
            || name.endsWith("package-info.class")
            || name.endsWith("module-info.class")) {
          continue;
        }
        final String binary = name.substring(0, name.length() - ".class".length());
        final int slash = binary.lastIndexOf('/');
        final String pkg = slash < 0 ? "" : binary.substring(0, slash).replace('/', '.');
        if (!exportedPackages.contains(pkg)) {
          continue; // a non-exported package is private business, not the published surface.
        }
        try (InputStream in = jar.getInputStream(entry)) {
          collectHelpers(new ClassReader(in), binary.substring(slash + 1), violations);
        }
      }
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot read jar " + bundle.ga(), ex);
    }
    return violations;
  }

  /**
   * Visit one class, adding {@code Simple#method} for each public-static behaviour method that is
   * neither a factory nor {@code @Exempt(INSTANCE_DISCIPLINE)}. A type-level exemption skips the
   * whole class; a method-level one skips that method (decided in the method's {@code visitEnd},
   * since the annotation is visited as the method is read).
   */
  private static void collectHelpers(
      ClassReader reader, String simpleName, List<String> violations) {
    final String internalName = reader.getClassName();
    final boolean isEnum = (reader.getAccess() & Opcodes.ACC_ENUM) != 0;
    reader.accept(
        new ClassVisitor(Opcodes.ASM9) {
          private boolean typeExempt;

          @Override
          public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            return exemptVisitor(desc, () -> typeExempt = true);
          }

          @Override
          public MethodVisitor visitMethod(
              int access, String method, String descriptor, String sig, String[] ex) {
            if (typeExempt || !isStaticHelper(access, method, descriptor, internalName, isEnum)) {
              return null;
            }
            return new MethodVisitor(Opcodes.ASM9) {
              private boolean methodExempt;

              @Override
              public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                return exemptVisitor(desc, () -> methodExempt = true);
              }

              @Override
              public void visitEnd() {
                if (!methodExempt) {
                  violations.add(simpleName + "#" + method);
                }
              }
            };
          }
        },
        ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
  }

  /**
   * An {@link AnnotationVisitor} that fires {@code onExempt} when an {@code @Exempt} (or a nested
   * one inside the {@code @ExemptAll} container) names {@link StagingGate#INSTANCE_DISCIPLINE} in
   * its {@code value}; {@code null} for any other annotation. Shared by the type- and method-level
   * scans.
   */
  private static AnnotationVisitor exemptVisitor(String desc, Runnable onExempt) {
    if (EXEMPT.equals(desc)) {
      return new ExemptValueVisitor(onExempt);
    }
    if (EXEMPT_ALL.equals(desc)) {
      return new AnnotationVisitor(Opcodes.ASM9) {
        @Override
        public AnnotationVisitor visitArray(String name) {
          return "value".equals(name) ? this : null;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String name, String elementDesc) {
          return new ExemptValueVisitor(onExempt); // each array element is an @Exempt
        }
      };
    }
    return null;
  }

  /**
   * Reads one {@code @Exempt}'s {@code value} enum and fires {@code onExempt} if it is this gate.
   */
  private static final class ExemptValueVisitor extends AnnotationVisitor {

    private final Runnable onExempt;

    ExemptValueVisitor(Runnable onExempt) {
      super(Opcodes.ASM9);
      this.onExempt = onExempt;
    }

    @Override
    public void visitEnum(String name, String enumDesc, String value) {
      if ("value".equals(name) && INSTANCE_DISCIPLINE.equals(value)) {
        onExempt.run();
      }
    }
  }

  /**
   * Whether {@code method} is exported {@code public static} behaviour — not a factory, not a
   * constructor/initializer, not an enum's synthetic accessors, and not returning its own type.
   */
  private static boolean isStaticHelper(
      int access, String method, String descriptor, String internalName, boolean isEnum) {
    if ((access & Opcodes.ACC_STATIC) == 0 || (access & Opcodes.ACC_PUBLIC) == 0) {
      return false; // instance method, or not part of the published static surface.
    }
    if (method.startsWith("<") || "main".equals(method)) {
      return false; // <clinit>/<init>, or a program entry point.
    }
    if (isEnum && ("values".equals(method) || "valueOf".equals(method))) {
      return false; // synthetic enum accessors.
    }
    if (FACTORY_NAMES.contains(method) || method.startsWith("from") || method.startsWith("new")) {
      return false; // the endorsed factory surface (incl. type-safe enum conversions).
    }
    final Type returnType = Type.getReturnType(descriptor);
    return !internalName.equals(returnType.getInternalName()); // returns self ⇒ fluent factory.
  }
}
