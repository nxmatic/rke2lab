package io.nxmatic.rke2lab.maven.staging;

import java.util.LinkedHashMap;
import java.util.Map;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The code side of the SCHEMA_CONCORD gate under the records-as-contract model: from staged
 * bytecode it builds two maps —
 *
 * <ul>
 *   <li>every {@code Coordinate} enum constant → its wire slug (read from the enum's {@code
 *       <clinit>}), so the gate knows the full set of coordinates that MUST have a wire-record;
 *   <li>every wire-record carrying {@code @DocumentContract(X)} → the coordinate slug X, so the
 *       gate knows which coordinates are already migrated.
 * </ul>
 *
 * A coordinate present in the first map but absent from the second is the WARN worklist entry (no
 * wire-record yet); when the two agree the gate can flip to ERROR.
 */
final class DocumentContractScan {

  /** ASM internal name of the {@code Coordinate} enum, e.g. {@code io/nxmatic/…/Coordinate}. */
  private final String coordinate;

  /**
   * ASM type descriptor of {@code @DocumentContract}, e.g. {@code Lio/nxmatic/…/DocumentContract;}.
   */
  private final String contractDesc;

  private final Map<String, String> coordinateConstToSlug = new LinkedHashMap<>();
  private final Map<String, String> slugToRecordInternalName = new LinkedHashMap<>();

  /**
   * @param seamPackage the seed-broker seam's exported package, dotted (the SAME string the caller
   *     selects the seam bundle by) — the single source both the bundle selection and this scan
   *     derive from, so a rename cannot make one drift silently past the other.
   */
  DocumentContractScan(String seamPackage) {
    final String seamInternal = seamPackage.replace('.', '/');
    this.coordinate = seamInternal + "/Coordinate";
    this.contractDesc = "L" + seamInternal + "/DocumentContract;";
  }

  /** ASM internal name of the {@code Coordinate} enum this scan indexes. */
  String coordinateInternalName() {
    return coordinate;
  }

  /** Index the Coordinate enum's constant→slug map from its {@code <clinit>} LDC pairs. */
  void indexCoordinate(byte[] coordinateClass) {
    new ClassReader(coordinateClass)
        .accept(
            new ClassVisitor(Opcodes.ASM9) {
              @Override
              public org.objectweb.asm.MethodVisitor visitMethod(
                  int a, String n, String d, String s, String[] e) {
                if (!"<clinit>".equals(n)) {
                  return null;
                }
                return new org.objectweb.asm.MethodVisitor(Opcodes.ASM9) {
                  private String pendingName;
                  private String pendingSlug;

                  @Override
                  public void visitLdcInsn(Object value) {
                    if (value instanceof String str) {
                      if (pendingName == null) {
                        pendingName = str;
                      } else {
                        pendingSlug = str;
                      }
                    }
                  }

                  @Override
                  public void visitFieldInsn(int opcode, String owner, String name, String desc) {
                    if (opcode == Opcodes.PUTSTATIC
                        && coordinate.equals(owner)
                        && ("L" + coordinate + ";").equals(desc)
                        && pendingName != null
                        && pendingSlug != null) {
                      coordinateConstToSlug.put(pendingName, pendingSlug);
                      pendingName = null;
                      pendingSlug = null;
                    }
                  }
                };
              }
            },
            ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
  }

  /**
   * If this class carries {@code @DocumentContract(X)}, record X's slug → the class's internal
   * name. The annotation's value is an enum reference {@code (Coordinate, "CONST")}; the const is
   * mapped to its slug via the already-indexed coordinate map.
   */
  void scan(String internalName, byte[] classfile) {
    new ClassReader(classfile)
        .accept(
            new ClassVisitor(Opcodes.ASM9) {
              @Override
              public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (!contractDesc.equals(descriptor)) {
                  return null;
                }
                return new AnnotationVisitor(Opcodes.ASM9) {
                  @Override
                  public void visitEnum(String name, String enumDesc, String constName) {
                    final String slug = coordinateConstToSlug.get(constName);
                    if (slug != null) {
                      slugToRecordInternalName.put(slug, internalName);
                    }
                  }
                };
              }
            },
            ClassReader.SKIP_CODE);
  }

  /** All coordinate slugs the enum declares — the set that MUST each have a wire-record. */
  Map<String, String> coordinateConstToSlug() {
    return coordinateConstToSlug;
  }

  /** Migrated coordinates: slug → wire-record internal name. */
  Map<String, String> slugToRecordInternalName() {
    return slugToRecordInternalName;
  }
}
