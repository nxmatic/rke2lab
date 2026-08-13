package io.seedmatic.rke2lab.seed.broker.port;

import java.util.ArrayList;
import java.util.List;

/**
 * A value's fil d'Ariane — its lineage as an ordered trail of {@link Breadcrumb}s, ROOT-FIRST (the
 * git commit it descends from) through to the coordinate it was filed under. Rides CLEAR on the
 * {@link SeedEnvelope}, OUTSIDE the sealed payload, so a SEALED value's lineage is traceable back
 * to its source commit WITHOUT the passphrase — the point of secured-value traceability (§
 * fil-d-ariane). Immutable: {@link #push} returns a NEW trail with the crumb appended, the receiver
 * unchanged.
 */
public record Trail(List<Breadcrumb> breadcrumbs) {

  public Trail {
    breadcrumbs = List.copyOf(breadcrumbs);
  }

  /** The empty trail — an unstamped value, or a store filed before any provenance root is known. */
  public static Trail empty() {
    return new Trail(List.of());
  }

  /** A new trail with {@code crumb} appended; this trail is left unchanged. */
  public Trail push(Breadcrumb crumb) {
    final List<Breadcrumb> appended = new ArrayList<>(breadcrumbs);
    appended.add(crumb);
    return new Trail(appended);
  }
}
