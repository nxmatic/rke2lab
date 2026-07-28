package io.nxmatic.rke2lab.maven.staging;

import java.util.List;
import java.util.Set;

/**
 * The build-time guard that a {@code type=dual-realm} carrier is JUSTIFIED (the {@code
 * DUAL_REALM_JUSTIFIED} law). A dual-realm library is staged as a bundle AND kept flat host-side —
 * a deliberate second copy — warranted ONLY when the flat/host realm actually uses it. If ZERO
 * flat-realm classes reference any package the carrier exports, the flat copy is dead weight and
 * the split is unjustified: the module should fold OSGi-only (drop the {@code type=dual-realm}
 * marker and be a plain staged bundle).
 *
 * <p>The count is a STATIC bytecode reference count (via {@link ReferencedTypes}), so it sees
 * UNEXERCISED references a booted test could not — the same reason {@code REALM_BOUNDARY} stays
 * static. It is the library analogue of the precedented {@code -port} host-import-count rule (a
 * {@code -port} stops being {@code type=seam} when zero host files import it). The earlier
 * flat∩export attempt false-flagged genuine both-realm libraries; the host-import count is the
 * RIGHT signal — a genuinely both-realm carrier HAS host importers, so a correct count never flags
 * it. See docs/architecture/osgi/staging-gates-governance-spec.adoc § DUAL_REALM_JUSTIFIED.
 */
final class DualRealmJustified {

  private final List<ResolvedBundle.ClassEntry> flatRealmClasses;

  DualRealmJustified(List<ResolvedBundle.ClassEntry> flatRealmClasses) {
    this.flatRealmClasses = flatRealmClasses;
  }

  /**
   * The (at most one) violation line for a dual-realm carrier that no flat-realm class references —
   * its flat copy is unused, so the split is unjustified. A carrier the host DOES use produces no
   * line. The carrier's OWN classes are skipped: a package references itself trivially, which is
   * not a host importer.
   */
  List<String> violations(ResolvedBundle dualRealm) {
    final Set<String> exported = dualRealm.ourExportedPackages();
    if (exported.isEmpty()) {
      return List.of(); // exports nothing — the flat-vs-OSGi split question does not arise.
    }
    for (ResolvedBundle.ClassEntry entry : flatRealmClasses) {
      if (exported.contains(packageOf(entry.binaryName()))) {
        continue; // the carrier's own class — not an outside host importer.
      }
      for (String referenced : ReferencedTypes.in(entry.bytes())) {
        if (exported.contains(referenced)) {
          return List.of(); // a flat/host class uses it — the dual-realm split is justified.
        }
      }
    }
    return List.of(
        "no flat/host class references its exported packages "
            + exported
            + " — the flat copy is unused; fold OSGi-only (drop type=dual-realm)");
  }

  private static String packageOf(String binaryName) {
    final int slash = binaryName.lastIndexOf('/');
    return slash < 0 ? "" : binaryName.substring(0, slash).replace('/', '.');
  }
}
