package io.seedmatic.rke2lab.worktree;

import io.seedmatic.rke2lab.seed.broker.port.SeedCoordinate;

/**
 * The worktree domain's CELLAR coordinate — where the worktree soil files its {@link WorktreeFacts}
 * harvest and the flat host fetches it back. A domain enum (routed by identity for OSGi-side
 * dispatch), but here it is a CELLAR key: {@code Cellar} store/fetch key on the {@code slug()} +
 * {@code domain()} wire STRINGS, not object identity, so a dual-realm enum works across the seam
 * exactly as {@code IncusGrowCoordinate.INSTANCE_GROW_PLAN} does — the OSGi soil stores under this
 * coordinate, the flat host fetches under its own copy. The soil is PLAYED via a seam {@code
 * RunbookCoordinate("worktree")} (broker routing needs a shared value-coordinate); the facts travel
 * through the cellar, not the broker reply. The slug matches {@link WorktreeFacts}'s
 * {@code @SeedContract} ({@code worktree-facts}), which {@code SeedCodec} verifies at decode.
 */
public enum WorktreeCoordinate implements SeedCoordinate {
  FACTS("worktree-facts");

  private static final String DOMAIN = "worktree";

  private final String slug;

  WorktreeCoordinate(String slug) {
    this.slug = slug;
  }

  @Override
  public String slug() {
    return slug;
  }

  @Override
  public String domain() {
    return DOMAIN;
  }
}
