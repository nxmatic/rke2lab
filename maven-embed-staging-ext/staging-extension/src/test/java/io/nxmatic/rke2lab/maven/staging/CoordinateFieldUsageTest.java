package io.nxmatic.rke2lab.maven.staging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

final class CoordinateFieldUsageTest {

  @Test
  void coordinateSlugMapExtractedFromBytecode() {
    // Generate a synthetic enum with the same bytecode shape as Coordinate:
    // public enum TestCoordinate {
    //   ALPHA("alpha-slug"),
    //   BETA("beta-slug");
    //   private final String slug;
    //   TestCoordinate(String slug) { this.slug = slug; }
    // }
    final byte[] syntheticEnum = generateTestCoordinateEnum();

    final CoordinateFieldUsage usage = new CoordinateFieldUsage();
    usage.indexCoordinate(syntheticEnum);

    // Verify the extracted map contains the 2 constants
    final Map<String, String> constToSlug = usage.coordinateConstToSlug();
    assertEquals(2, constToSlug.size(), "should extract exactly 2 enum constants");
    assertEquals("alpha-slug", constToSlug.get("ALPHA"), "ALPHA should map to alpha-slug");
    assertEquals("beta-slug", constToSlug.get("BETA"), "BETA should map to beta-slug");

    // Verify $VALUES is NOT in the map
    assertFalse(
        constToSlug.containsKey("$VALUES"),
        "$VALUES array field should not be extracted as a coordinate");
  }

  /**
   * Generates bytecode for: {@code enum TestCoordinate { ALPHA("alpha-slug"), BETA("beta-slug");
   * private final String slug; TestCoordinate(String slug) { this.slug = slug; } }}
   */
  private static byte[] generateTestCoordinateEnum() {
    final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
    cw.visit(
        Opcodes.V17,
        Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER | Opcodes.ACC_ENUM,
        "io/nxmatic/rke2lab/world/gateway/port/Coordinate",
        "Ljava/lang/Enum<Lio/nxmatic/rke2lab/world/gateway/port/Coordinate;>;",
        "java/lang/Enum",
        null);

    // Field: public static final TestCoordinate ALPHA
    cw.visitField(
        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM,
        "ALPHA",
        "Lio/nxmatic/rke2lab/world/gateway/port/Coordinate;",
        null,
        null);

    // Field: public static final TestCoordinate BETA
    cw.visitField(
        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM,
        "BETA",
        "Lio/nxmatic/rke2lab/world/gateway/port/Coordinate;",
        null,
        null);

    // Field: private static final TestCoordinate[] $VALUES
    cw.visitField(
        Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
        "$VALUES",
        "[Lio/nxmatic/rke2lab/world/gateway/port/Coordinate;",
        null,
        null);

    // Field: private final String slug
    cw.visitField(
        Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "slug", "Ljava/lang/String;", null, null);

    // Constructor: TestCoordinate(String name, int ordinal, String slug)
    MethodVisitor mv =
        cw.visitMethod(
            Opcodes.ACC_PRIVATE, "<init>", "(Ljava/lang/String;ILjava/lang/String;)V", null, null);
    mv.visitCode();
    mv.visitVarInsn(Opcodes.ALOAD, 0);
    mv.visitVarInsn(Opcodes.ALOAD, 1);
    mv.visitVarInsn(Opcodes.ILOAD, 2);
    mv.visitMethodInsn(
        Opcodes.INVOKESPECIAL, "java/lang/Enum", "<init>", "(Ljava/lang/String;I)V", false);
    mv.visitVarInsn(Opcodes.ALOAD, 0);
    mv.visitVarInsn(Opcodes.ALOAD, 3);
    mv.visitFieldInsn(
        Opcodes.PUTFIELD,
        "io/nxmatic/rke2lab/world/gateway/port/Coordinate",
        "slug",
        "Ljava/lang/String;");
    mv.visitInsn(Opcodes.RETURN);
    mv.visitMaxs(3, 4);
    mv.visitEnd();

    // Static initializer: <clinit>
    mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
    mv.visitCode();

    // ALPHA = new TestCoordinate("ALPHA", 0, "alpha-slug");
    mv.visitTypeInsn(Opcodes.NEW, "io/nxmatic/rke2lab/world/gateway/port/Coordinate");
    mv.visitInsn(Opcodes.DUP);
    mv.visitLdcInsn("ALPHA");
    mv.visitInsn(Opcodes.ICONST_0);
    mv.visitLdcInsn("alpha-slug");
    mv.visitMethodInsn(
        Opcodes.INVOKESPECIAL,
        "io/nxmatic/rke2lab/world/gateway/port/Coordinate",
        "<init>",
        "(Ljava/lang/String;ILjava/lang/String;)V",
        false);
    mv.visitFieldInsn(
        Opcodes.PUTSTATIC,
        "io/nxmatic/rke2lab/world/gateway/port/Coordinate",
        "ALPHA",
        "Lio/nxmatic/rke2lab/world/gateway/port/Coordinate;");

    // BETA = new TestCoordinate("BETA", 1, "beta-slug");
    mv.visitTypeInsn(Opcodes.NEW, "io/nxmatic/rke2lab/world/gateway/port/Coordinate");
    mv.visitInsn(Opcodes.DUP);
    mv.visitLdcInsn("BETA");
    mv.visitInsn(Opcodes.ICONST_1);
    mv.visitLdcInsn("beta-slug");
    mv.visitMethodInsn(
        Opcodes.INVOKESPECIAL,
        "io/nxmatic/rke2lab/world/gateway/port/Coordinate",
        "<init>",
        "(Ljava/lang/String;ILjava/lang/String;)V",
        false);
    mv.visitFieldInsn(
        Opcodes.PUTSTATIC,
        "io/nxmatic/rke2lab/world/gateway/port/Coordinate",
        "BETA",
        "Lio/nxmatic/rke2lab/world/gateway/port/Coordinate;");

    // $VALUES = new TestCoordinate[] { ALPHA, BETA };
    mv.visitInsn(Opcodes.ICONST_2);
    mv.visitTypeInsn(Opcodes.ANEWARRAY, "io/nxmatic/rke2lab/world/gateway/port/Coordinate");
    mv.visitInsn(Opcodes.DUP);
    mv.visitInsn(Opcodes.ICONST_0);
    mv.visitFieldInsn(
        Opcodes.GETSTATIC,
        "io/nxmatic/rke2lab/world/gateway/port/Coordinate",
        "ALPHA",
        "Lio/nxmatic/rke2lab/world/gateway/port/Coordinate;");
    mv.visitInsn(Opcodes.AASTORE);
    mv.visitInsn(Opcodes.DUP);
    mv.visitInsn(Opcodes.ICONST_1);
    mv.visitFieldInsn(
        Opcodes.GETSTATIC,
        "io/nxmatic/rke2lab/world/gateway/port/Coordinate",
        "BETA",
        "Lio/nxmatic/rke2lab/world/gateway/port/Coordinate;");
    mv.visitInsn(Opcodes.AASTORE);
    mv.visitFieldInsn(
        Opcodes.PUTSTATIC,
        "io/nxmatic/rke2lab/world/gateway/port/Coordinate",
        "$VALUES",
        "[Lio/nxmatic/rke2lab/world/gateway/port/Coordinate;");

    mv.visitInsn(Opcodes.RETURN);
    mv.visitMaxs(4, 0);
    mv.visitEnd();

    cw.visitEnd();
    return cw.toByteArray();
  }
}
