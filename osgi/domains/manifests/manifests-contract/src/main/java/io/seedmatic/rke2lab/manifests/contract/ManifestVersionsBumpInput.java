package io.seedmatic.rke2lab.manifests.contract;

import io.seedmatic.rke2lab.manifests.ingress.BumpLevel;
import io.seedmatic.rke2lab.manifests.ingress.Component;
import io.seedmatic.rke2lab.seed.broker.port.Amendment;
import io.seedmatic.rke2lab.seed.broker.port.SeedContract;
import java.util.Optional;

/**
 * The wire contract for the manifests {@code versions} bump trigger — the activation payload the
 * {@code manifests-cli versions} verb sows to play the bump scion. Its single {@link
 * Amendment#FACET} component is the operator's bump policy, TYPED end-to-end: {@link BumpLevel} and
 * {@link Component} are dual-realm {@code WireEnum}s (ingress), so the host builds the facet typed,
 * it crosses the membrane by slug, and the scion decodes it back typed — no loose String on either
 * side.
 *
 * <p>Its own plant on its own {@link ManifestsCoordinate#VERSIONS} coordinate — never a fork of the
 * synthesis runbook. All the bump knowledge (reading the {@link Component} pins, querying GitHub,
 * rewriting the source, refreshing the vendored manifests, committing as the bot) lives OSGi-side.
 */
@SeedContract("runbook")
public record ManifestVersionsBumpInput(@Amendment(Amendment.FACET) BumpFacet facet) {

  /**
   * The bump policy the operator carries — the single {@link Amendment#FACET} component so the role
   * binds to ONE field. {@code level} is the bump CEILING; {@code apply} writes the change (else
   * reports); {@code component} narrows to one {@link Component}, empty for all. The sower (the
   * {@code manifests-cli versions} verb) builds it and sows it — a mandatory FACET, no door
   * default.
   */
  public record BumpFacet(BumpLevel level, boolean apply, Optional<Component> component) {}
}
