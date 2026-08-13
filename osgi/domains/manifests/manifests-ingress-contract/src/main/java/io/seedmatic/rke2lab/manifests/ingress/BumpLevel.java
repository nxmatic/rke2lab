package io.seedmatic.rke2lab.manifests.ingress;

import io.seedmatic.rke2lab.seed.broker.port.WireEnum;
import java.util.Arrays;

/**
 * The bump CEILING the operator authorises for the {@code versions} bumper — a {@link WireEnum} so
 * the bump facet carries it typed and it crosses the host↔OSGi membrane by {@code slug()}, never a
 * loose String. The level is a ceiling, not a requirement: the bumper picks the HIGHEST release the
 * level admits, so {@code major} takes the newest outright ("degrading" to a minor/micro when that
 * is all that is newer), {@code minor} stays within the current major, {@code micro} within the
 * current major.minor. Dual-realm (ingress) so the flat host builds it typed too.
 */
public enum BumpLevel implements WireEnum {
  MAJOR("major"),
  MINOR("minor"),
  MICRO("micro");

  private final String slug;

  BumpLevel(final String slug) {
    this.slug = slug;
  }

  @Override
  public String slug() {
    return slug;
  }

  /**
   * The level for the given slug, defaulting to {@link #MINOR} for a blank/unknown value (the CLI's
   * default posture) — {@code patch} is accepted as an alias for {@link #MICRO}.
   */
  public static BumpLevel fromSlug(final String slug) {
    final String normalized = slug == null ? "" : slug.trim().toLowerCase();
    if ("patch".equals(normalized)) {
      return MICRO;
    }
    return Arrays.stream(values())
        .filter(level -> level.slug.equals(normalized))
        .findFirst()
        .orElse(MINOR);
  }
}
