package io.seedmatic.rke2lab.osgibench.fragment;

/**
 * The service a fragment-contributed {@code @Component} publishes — the proof's observable. It
 * lives in the HOST bundle (exported, shared with the test via {@code system.packages.extra}) so
 * the test can {@code awaitService(ContributedService.class)} typed, while the {@code @Component}
 * that provides it lives in an attached FRAGMENT. If the service reaches the registry, SCR honored
 * the fragment's {@code Service-Component} header — the load-bearing unknown of the
 * fragment-contribution model.
 */
public interface ContributedService {
  String contribution();
}
