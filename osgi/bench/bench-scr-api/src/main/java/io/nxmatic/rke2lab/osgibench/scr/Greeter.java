package io.nxmatic.rke2lab.osgibench.scr;

/**
 * The service a {@code @Component} provider publishes. Lives in its OWN api bundle, exported and
 * shared with the test via {@code system.packages.extra} (the typed-access trick the bench proved
 * with {@code MetaTypeService}). Keeping the API independent of the provider is what lets the
 * anti-cheat run the consumer with the provider bundle ABSENT: the consumer still resolves (the API
 * is present) but SCR leaves it unsatisfied (no {@link Greeter} service) — resolution succeeds,
 * activation does not.
 */
public interface Greeter {
  String greet(String name);
}
