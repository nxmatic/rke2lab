package io.nxmatic.rke2lab.maven.staging;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Discovers, from bundle bytecode, which {@code WorldGatewayCatalog.FIELD_*} VALUES each {@code
 * Coordinate} slug's code references — the code side of the SCHEMA_CONCORD concord check.
 *
 * <p>Two passes: first read {@code WorldGatewayCatalog} to map each {@code FIELD_*} field name to
 * its inlined {@code ConstantValue} String (the wire key); and read {@code Coordinate} to map each
 * enum constant to its slug. Then, for each class, collect the {@code FIELD_*} GETSTATICs and the
 * {@code Coordinate.<X>} GETSTATICs it references; a class that names exactly one coordinate
 * contributes its fields to that coordinate's slug. (Class-granularity — sufficient for the 2C
 * reality where each producer/consumer is single-coordinate; see plan RISK NOTE.)
 */
final class CoordinateFieldUsage {

  private static final String CATALOG = "io/nxmatic/rke2lab/world/gateway/port/WorldGatewayCatalog";
  private static final String COORDINATE = "io/nxmatic/rke2lab/world/gateway/port/Coordinate";

  private final Map<String, String> fieldNameToValue =
      new LinkedHashMap<>(); // FIELD_X -> "scenarioId"
  private final Map<String, String> coordinateConstToSlug =
      new LinkedHashMap<>(); // READINESS_VERDICT -> "readiness-verdict"
  private final Map<String, Set<String>> fieldsBySlug = new LinkedHashMap<>();

  /**
   * Index the catalog constant values and the coordinate slugs (call once with their classfiles).
   */
  void indexCatalog(byte[] catalogClass) {
    new ClassReader(catalogClass)
        .accept(
            new ClassVisitor(Opcodes.ASM9) {
              @Override
              public FieldVisitor visitField(
                  int access, String name, String descriptor, String signature, Object value) {
                if (name.startsWith("FIELD_") && value instanceof String s) {
                  fieldNameToValue.put(name, s);
                }
                return null;
              }
            },
            ClassReader.SKIP_CODE);
  }

  /**
   * Provide the Coordinate enum's constant→slug map (parsed from the enum source or a known map).
   */
  void indexCoordinate(Map<String, String> constToSlug) {
    coordinateConstToSlug.putAll(constToSlug);
  }

  /** Scan one bundle class: attribute its FIELD_* uses to the single Coordinate it names. */
  void scan(byte[] classfile) {
    final Set<String> fields = new LinkedHashSet<>();
    final Set<String> coords = new LinkedHashSet<>();
    new ClassReader(classfile)
        .accept(
            new ClassVisitor(Opcodes.ASM9) {
              @Override
              public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                return new MethodVisitor(Opcodes.ASM9) {
                  @Override
                  public void visitFieldInsn(
                      int opcode, String owner, String name, String descriptor) {
                    if (opcode == Opcodes.GETSTATIC
                        && CATALOG.equals(owner)
                        && name.startsWith("FIELD_")) {
                      fields.add(name);
                    }
                    if (opcode == Opcodes.GETSTATIC && COORDINATE.equals(owner)) {
                      coords.add(name);
                    }
                  }
                };
              }
            },
            ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

    if (coords.size() != 1 || fields.isEmpty()) {
      return; // attribute only when a class names exactly one coordinate (class-granularity)
    }
    final String slug = coordinateConstToSlug.get(coords.iterator().next());
    if (slug == null) {
      return;
    }
    final Set<String> values = fieldsBySlug.computeIfAbsent(slug, k -> new LinkedHashSet<>());
    for (String f : fields) {
      final String v = fieldNameToValue.get(f);
      if (v != null) {
        values.add(v);
      }
    }
  }

  Map<String, Set<String>> fieldsByCoordinateSlug() {
    return fieldsBySlug;
  }
}
