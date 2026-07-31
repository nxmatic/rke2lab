package io.nxmatic.rke2lab.seed.bdd;

import io.nxmatic.rke2lab.seed.broker.port.OpaqueCellar;
import io.nxmatic.rke2lab.seed.broker.port.Parcel;
import io.nxmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.util.List;
import java.util.Optional;

/**
 * The offline durable backend — a black-hole {@link OpaqueCellar} for a standalone CLI run that is
 * NOT driven by a persistent commissioner (no Pulumi stack, no doctor journal). It is the ephemeral
 * antonym of {@code PulumiCellar}: the run's transactional {@code ScenarioCellar} still serves
 * read-your-writes WITHIN the process, but its end-of-run drain lands here and is discarded — a
 * standalone synthesis persists nothing beyond its materialised files.
 *
 * <p>Why it exists at all rather than skipping the drain: {@code ScenarioCellarExtension} drains a
 * ROOT run to whatever {@code OpaqueCellar} the registry holds, and fails if none is present. A
 * host driver with no durable store (the manifests / netplan CLIs) publishes THIS so the drain has
 * a sink — and since those scions store nothing, the sink is never actually written. The honest
 * realisation of "offline": a backend that exists but keeps nothing.
 */
public final class EphemeralCellar implements OpaqueCellar {

  @Override
  public void store(Parcel parcel, SeedEnvelope vegetal) {
    // black hole: an offline run persists nothing.
  }

  @Override
  public List<SeedEnvelope> fetch(Parcel parcel) {
    return List.of();
  }

  @Override
  public Optional<SeedEnvelope> fetch(Parcel parcel, SeedCoordinate coordinate) {
    return Optional.empty();
  }

  @Override
  public Optional<SeedEnvelope> withdraw(Parcel parcel, SeedCoordinate coordinate) {
    return Optional.empty();
  }

  @Override
  public List<Parcel> neighbours(Parcel parcel) {
    return List.of();
  }
}
