package io.nxmatic.rke2lab.maven.staging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.RecordComponentVisitor;

/**
 * Projects a wire-record's {@code RecordComponents} to a JSON Schema, by ASM — the build-time
 * "schema generated from the record" the {@code SCHEMA_CONCORD} gate checks. No hand-written
 * schema: the record's components ARE the contract. The vocabulary the six Document coordinates
 * need:
 *
 * <ul>
 *   <li>String / Instant → {@code string} (Instant carries {@code format: date-time});
 *   <li>boolean, integer kinds, floating kinds → the matching JSON scalar;
 *   <li>{@code Optional<T>} → the schema of {@code T}, and the property is NOT required;
 *   <li>{@code List<T>} → {@code array} whose {@code items} is the schema of {@code T};
 *   <li>a nested wire-record → a nested {@code object} (recurse over its components);
 *   <li>a seam enum → {@code string} constrained to the enum's wire slugs;
 *   <li>an unresolved type (Map, Object, a sub-tree the producer copies opaquely) → an open {@code
 *       object} — the contract is "this slot is an object", its inner shape owned where produced.
 * </ul>
 *
 * <p>Type bytes are resolved by internal name through the supplied function (the staged
 * world-gateway bundle's class entries), so nested wire-records and seam enums resolve while JDK
 * and opaque types do not (and become open objects).
 */
final class RecordSchemaProjector {

  private static final String SCHEMA_URI = "https://json-schema.org/draft/2020-12/schema";

  private final Function<String, byte[]> bytesByInternalName;
  private final ObjectMapper mapper = new ObjectMapper();

  RecordSchemaProjector(Function<String, byte[]> bytesByInternalName) {
    this.bytesByInternalName = bytesByInternalName;
  }

  /** The top-level object schema for a wire-record, carrying the {@code $schema} meta key. */
  ObjectNode projectRoot(String recordInternalName) {
    final ObjectNode schema = projectObject(recordInternalName, new LinkedHashSet<>());
    schema.put("$schema", SCHEMA_URI);
    return schema;
  }

  private ObjectNode projectObject(String recordInternalName, Set<String> seen) {
    final ObjectNode schema = mapper.createObjectNode();
    schema.put("type", "object");
    final byte[] bytes = bytesByInternalName.apply(recordInternalName);
    if (bytes == null || !seen.add(recordInternalName)) {
      return schema; // unresolved or cycle → open object
    }
    final ObjectNode properties = schema.putObject("properties");
    final ArrayNode required = mapper.createArrayNode();
    for (Component c : readComponents(bytes)) {
      properties.set(c.name(), schemaForComponent(c, seen));
      if (!"java/util/Optional".equals(rawTypeOf(c.descriptor()))) {
        required.add(c.name());
      }
    }
    if (!required.isEmpty()) {
      schema.set("required", required);
    }
    return schema;
  }

  private ObjectNode schemaForComponent(Component c, Set<String> seen) {
    final String raw = rawTypeOf(c.descriptor());
    if ("java/util/Optional".equals(raw)) {
      return schemaForType(typeArgumentOf(c.signature()), seen);
    }
    if ("java/util/List".equals(raw)) {
      final ObjectNode array = mapper.createObjectNode();
      array.put("type", "array");
      array.set("items", schemaForType(typeArgumentOf(c.signature()), seen));
      return array;
    }
    return schemaForDescriptor(c.descriptor(), seen);
  }

  private ObjectNode schemaForDescriptor(String descriptor, Set<String> seen) {
    return switch (descriptor) {
      case "Z" -> scalar("boolean");
      case "B", "S", "I", "J" -> scalar("integer");
      case "F", "D" -> scalar("number");
      default -> {
        if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
          yield schemaForType(descriptor.substring(1, descriptor.length() - 1), seen);
        }
        yield mapper.createObjectNode().put("type", "object"); // arrays/unknown → open object
      }
    };
  }

  private ObjectNode schemaForType(String internalName, Set<String> seen) {
    if (internalName == null) {
      return mapper.createObjectNode().put("type", "object");
    }
    switch (internalName) {
      case "java/lang/String":
        return scalar("string");
      case "java/lang/Boolean":
        return scalar("boolean");
      case "java/lang/Byte":
      case "java/lang/Short":
      case "java/lang/Integer":
      case "java/lang/Long":
        return scalar("integer");
      case "java/lang/Float":
      case "java/lang/Double":
        return scalar("number");
      case "java/time/Instant":
        final ObjectNode instant = scalar("string");
        instant.put("format", "date-time");
        return instant;
      default:
        final byte[] bytes = bytesByInternalName.apply(internalName);
        if (bytes == null) {
          return mapper.createObjectNode().put("type", "object"); // Map/Object/opaque sub-tree
        }
        final Kind kind = kindOf(bytes);
        if (kind == Kind.ENUM) {
          return enumSchema(bytes);
        }
        if (kind == Kind.RECORD) {
          return projectObject(internalName, seen);
        }
        return mapper.createObjectNode().put("type", "object");
    }
  }

  private ObjectNode scalar(String type) {
    return mapper.createObjectNode().put("type", type);
  }

  /** A seam enum serializes to its wire slug; constrain the string to that slug set. */
  private ObjectNode enumSchema(byte[] bytes) {
    final ObjectNode node = scalar("string");
    final Set<String> slugs = enumSlugs(bytes);
    if (!slugs.isEmpty()) {
      final ArrayNode values = node.putArray("enum");
      slugs.forEach(values::add);
    }
    return node;
  }

  // ---- ASM readers -------------------------------------------------------------------------

  private record Component(String name, String descriptor, String signature) {}

  private enum Kind {
    RECORD,
    ENUM,
    OTHER
  }

  private static List<Component> readComponents(byte[] bytes) {
    final List<Component> out = new ArrayList<>();
    new ClassReader(bytes)
        .accept(
            new ClassVisitor(Opcodes.ASM9) {
              @Override
              public RecordComponentVisitor visitRecordComponent(
                  String name, String descriptor, String signature) {
                out.add(new Component(name, descriptor, signature));
                return null;
              }
            },
            ClassReader.SKIP_CODE);
    return out;
  }

  private static Kind kindOf(byte[] bytes) {
    final Kind[] kind = {Kind.OTHER};
    new ClassReader(bytes)
        .accept(
            new ClassVisitor(Opcodes.ASM9) {
              @Override
              public void visit(
                  int v, int access, String name, String sig, String superName, String[] ifs) {
                if ("java/lang/Record".equals(superName)) {
                  kind[0] = Kind.RECORD;
                } else if ("java/lang/Enum".equals(superName)) {
                  kind[0] = Kind.ENUM;
                }
              }
            },
            ClassReader.SKIP_CODE);
    return kind[0];
  }

  /**
   * The enum's wire slugs: the {@code <clinit>} LDC string constants minus the enum constant NAMES.
   * Each constant is built as {@code new E("NAME", ordinal, "slug")}; the names are the {@code
   * ACC_ENUM} fields, so the remaining LDC strings are exactly the slugs. Order-independent (the
   * schema needs the set, not the sequence). Empty if the enum has no slug argument.
   */
  private static Set<String> enumSlugs(byte[] bytes) {
    final Set<String> constantNames = new LinkedHashSet<>();
    final Set<String> ldcStrings = new LinkedHashSet<>();
    new ClassReader(bytes)
        .accept(
            new ClassVisitor(Opcodes.ASM9) {
              @Override
              public FieldVisitor visitField(
                  int access, String name, String desc, String sig, Object value) {
                if ((access & Opcodes.ACC_ENUM) != 0) {
                  constantNames.add(name);
                }
                return null;
              }

              @Override
              public MethodVisitor visitMethod(
                  int access, String name, String desc, String sig, String[] exceptions) {
                if (!"<clinit>".equals(name)) {
                  return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                  @Override
                  public void visitLdcInsn(Object value) {
                    if (value instanceof String s) {
                      ldcStrings.add(s);
                    }
                  }
                };
              }
            },
            ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    ldcStrings.removeAll(constantNames);
    return ldcStrings;
  }

  // ---- descriptor / signature parsing -----------------------------------------------------

  /**
   * The raw internal name of a field descriptor: {@code Ljava/util/List;} → {@code java/util/List}.
   */
  private static String rawTypeOf(String descriptor) {
    if (descriptor != null && descriptor.startsWith("L") && descriptor.endsWith(";")) {
      return descriptor.substring(1, descriptor.length() - 1);
    }
    return descriptor;
  }

  /**
   * The internal name of the first type argument of a generic signature, or {@code null}. {@code
   * Ljava/util/List<Lfoo/Bar;>;} → {@code foo/Bar}; nested generics ({@code List<List<…>>}) yield
   * the inner raw type ({@code java/util/List}), which resolves to nothing → an open object.
   */
  private static String typeArgumentOf(String signature) {
    if (signature == null) {
      return null;
    }
    final int open = signature.indexOf('<');
    if (open < 0) {
      return null;
    }
    final int close = matchingAngle(signature, open);
    if (close < 0) {
      return null;
    }
    final String arg = signature.substring(open + 1, close);
    if (!arg.startsWith("L")) {
      return null; // primitive array / wildcard / type var → opaque
    }
    int end = open + 1 + 1;
    while (end < signature.length()) {
      final char ch = signature.charAt(end);
      if (ch == ';' || ch == '<') {
        break;
      }
      end++;
    }
    return signature.substring(open + 2, end);
  }

  private static int matchingAngle(String s, int open) {
    int depth = 0;
    for (int i = open; i < s.length(); i++) {
      if (s.charAt(i) == '<') {
        depth++;
      } else if (s.charAt(i) == '>') {
        depth--;
        if (depth == 0) {
          return i;
        }
      }
    }
    return -1;
  }
}
