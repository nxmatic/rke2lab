package io.seedmatic.rke2lab.maven.staging;

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
import org.objectweb.asm.Opcodes;

/**
 * The build-time purity check OF a {@code type=contract} {@link ResolvedBundle} — the law that
 * gives the {@link io.seedmatic.rke2lab.osgi.bnd.EmbedCapability#TYPE_CONTRACT contract} category
 * its meaning: a domain-contract bundle exports its data vocabulary (records, enums, sealed ADT
 * roots) AND the service interfaces a consumer resolves from the registry — but NEVER a concrete
 * class (an implementation belongs in the domain's {@code -core}, whose {@code @Component} pulls
 * SCR). It reads each exported top-level class through ASM (bytecode only, no class linking — the
 * records reference jackson / systemd types the extension realm does not carry), and reports every
 * exported type that is a concrete class. The staging strategy turns a non-empty report into a
 * build failure.
 *
 * <p>Widened from the former record-purity guard (records / enums / sealed only): a contract is a
 * record bundle that may ALSO carry interfaces, so the "interface allowed only as a sealed ADT
 * root" restriction is lifted — any interface is a legitimate contract face; only concrete classes
 * are the violation.
 *
 * <p>An INSTANCE of the bundle it checks (reached by {@link ResolvedBundle#contractPurity()}), not
 * a static helper — the check is navigable back to its subject, like the rest of this model
 * (object-graph-navigability).
 *
 * <p>Only TOP-LEVEL types are policed. A record's nested {@code Builder} (a plain class, the
 * endorsed construction contract for a record with a builder) carries a {@code $} in its class name
 * and is skipped — it is the record's own construction, not a separately-exported behavior type.
 */
final class ContractPurity {

  private final ResolvedBundle bundle;

  ContractPurity(ResolvedBundle bundle) {
    this.bundle = bundle;
  }

  /**
   * The fully-qualified names of exported top-level types that are NOT a record/enum/sealed-ADT.
   */
  List<String> violations() {
    final Set<String> exportedPackages = bundle.ourExportedPackages();
    final List<String> violations = new ArrayList<>();
    try (JarFile jar = new JarFile(bundle.file().orElseThrow())) {
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
          if (!isContractType(new ClassReader(in))) {
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
   * A record, an enum, or ANY interface (a service contract or a sealed ADT root) — the shapes a
   * contract bundle owns. A concrete class is the violation: an implementation belongs in the
   * domain's {@code -core}, never on the contract face (it would pull behavior — and, if a
   * {@code @Component}, the SCR extender — into a bundle consumers import for the interface alone).
   */
  private static boolean isContractType(ClassReader reader) {
    final int access = reader.getAccess();
    if ((access & Opcodes.ACC_RECORD) != 0 || "java/lang/Record".equals(reader.getSuperName())) {
      return true;
    }
    if ((access & Opcodes.ACC_ENUM) != 0) {
      return true;
    }
    // Any interface is a legitimate contract face — a service interface a consumer resolves, or a
    // sealed ADT root. Annotations are interfaces too (ACC_INTERFACE set), and belong on a
    // contract.
    return (access & Opcodes.ACC_INTERFACE) != 0;
  }
}
