package io.nxmatic.rke2lab.seed.broker.port;

/**
 * The cellar's OWN infrastructure coordinates — foundation keys the transactional cellar reads
 * structurally, owned by no domain. {@link #RUN_PROVENANCE} is the run's git root breadcrumb (its
 * fil d'Ariane root): the first crossing that harvests HEAD files a {@link Breadcrumb} here, and
 * {@code ScenarioCellar} reads it back at every {@code store} to stamp each value's {@link Trail}.
 * A foundation coordinate (not a domain enum) so the generic cellar can reference it without any
 * domain dependency — the git sha enters the foundation as a flat breadcrumb, filed under a
 * well-known key, and propagates to sibling crossings through the ordinary transactional
 * inheritance (§ fil-d-ariane).
 */
public enum CellarCoordinate implements SeedCoordinate {
  RUN_PROVENANCE("run-provenance");

  private static final String DOMAIN = "cellar";

  private final String slug;

  CellarCoordinate(String slug) {
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
