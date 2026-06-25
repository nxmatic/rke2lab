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
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The build-time purity check OF a {@code type=record} {@link ResolvedBundle} — the law that gives
 * the {@link io.nxmatic.rke2lab.osgi.bnd.EmbedCapability#TYPE_RECORD record} category its meaning:
 * a pure-data bundle exports only records, enums, and sealed ADT roots, never behavior. It reads
 * each exported top-level class through ASM (bytecode only, no class linking — the records
 * reference jackson / systemd types the extension realm does not carry), and reports every exported
 * type that is none of those. The staging strategy turns a non-empty report into a build failure.
 *
 * <p>An INSTANCE of the bundle it checks (reached by {@link ResolvedBundle#recordPurity()}), not a
 * static helper — the check is navigable back to its subject, like the rest of this model
 * (object-graph-navigability).
 *
 * <p>Only TOP-LEVEL types are policed. A record's nested {@code Builder} (a plain class, the
 * endorsed construction contract for a record with a builder) carries a {@code $} in its class name
 * and is skipped — it is the record's own construction, not a separately-exported behavior type.
 */
final class RecordPurity {

  private final ResolvedBundle bundle;

  RecordPurity(ResolvedBundle bundle) {
    this.bundle = bundle;
  }

  /**
   * The fully-qualified names of exported top-level types that are NOT a record/enum/sealed-ADT.
   */
  List<String> violations() {
    final Set<String> exportedPackages = bundle.exports().names();
    final List<String> violations = new ArrayList<>();
    try (JarFile jar = new JarFile(bundle.file())) {
      final Enumeration<JarEntry> entries = jar.entries();
      while (entries.hasMoreElements()) {
        final JarEntry entry = entries.nextElement();
        final String name = entry.getName();
        if (!name.endsWith(".class")
            || name.contains("$") // nested type — part of its enclosing record (e.g. a Builder)
            || name.endsWith("package-info.class")
            || name.endsWith("module-info.class")) {
          continue;
        }
        final String binary = name.substring(0, name.length() - ".class".length());
        final int slash = binary.lastIndexOf('/');
        final String pkg = slash < 0 ? "" : binary.substring(0, slash).replace('/', '.');
        if (!exportedPackages.contains(pkg)) {
          continue; // a non-exported package is the bundle's private business, not the seam.
        }
        try (InputStream in = jar.getInputStream(entry)) {
          if (!isPureData(new ClassReader(in))) {
            violations.add(binary.replace('/', '.'));
          }
        }
      }
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot read records jar " + bundle.ga(), ex);
    }
    return violations;
  }

  /**
   * A record, an enum, or a sealed interface (an ADT root) — the only shapes a record bundle owns.
   */
  private static boolean isPureData(ClassReader reader) {
    final int access = reader.getAccess();
    if ((access & Opcodes.ACC_RECORD) != 0 || "java/lang/Record".equals(reader.getSuperName())) {
      return true;
    }
    if ((access & Opcodes.ACC_ENUM) != 0) {
      return true;
    }
    if ((access & Opcodes.ACC_INTERFACE) != 0) {
      return isSealed(reader); // an interface is allowed ONLY as a sealed ADT root, not a contract.
    }
    return false;
  }

  /**
   * Whether the class carries a {@code PermittedSubclasses} attribute — i.e. it is {@code sealed}.
   */
  private static boolean isSealed(ClassReader reader) {
    final boolean[] sealed = {false};
    reader.accept(
        new ClassVisitor(Opcodes.ASM9) {
          @Override
          public void visitPermittedSubclass(String permittedSubclass) {
            sealed[0] = true;
          }
        },
        ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    return sealed[0];
  }
}
