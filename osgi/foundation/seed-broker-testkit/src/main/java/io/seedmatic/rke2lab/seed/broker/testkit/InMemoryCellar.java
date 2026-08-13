package io.seedmatic.rke2lab.seed.broker.testkit;

import io.seedmatic.rke2lab.seed.broker.port.Cellar;
import io.seedmatic.rke2lab.seed.broker.port.Parcel;
import io.seedmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.Sensitivity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An in-memory {@link Cellar} for tests — it holds decoded values directly (no codec, no backend),
 * so a test seeds the cases a unit under test reads and asserts on what it stored. It is the honest
 * stand-in when the code DOES use the cellar (unlike {@link RefusingCellar}, for code that must
 * not): a test pre-fills the cases a unit reads back and asserts on what it derived from them.
 *
 * <p>Semantics mirror the real cellar's shape without its transaction: {@code store} appends to a
 * per-{@code (parcel, coordinate)} timeline (last wins for the peek); {@code fetch(Class)} returns
 * the whole parcel timeline in store order, skipping values not assignable to the type (the
 * fail-at-end tolerance); {@code fetch(coordinate, Class)} peeks the last value; {@code withdraw}
 * empties the case; {@code neighbours} is the parcel alone.
 */
public final class InMemoryCellar implements Cellar {

  private final Map<Parcel, Map<String, List<Object>>> byParcel = new LinkedHashMap<>();

  @Override
  public <T> void store(
      Parcel parcel, SeedCoordinate coordinate, T value, Sensitivity sensitivity) {
    // Holds decoded values directly (no codec, no cipher), so sensitivity is a no-op here — the
    // seal/reveal round-trip is exercised on the real CodecCellar / the cipher's own test.
    byParcel
        .computeIfAbsent(parcel, p -> new LinkedHashMap<>())
        .computeIfAbsent(coordinate.slug(), c -> new ArrayList<>())
        .add(value);
  }

  @Override
  public <T> List<T> fetch(Parcel parcel, Class<T> type) {
    final List<T> timeline = new ArrayList<>();
    for (List<Object> caseValues : byParcel.getOrDefault(parcel, Map.of()).values()) {
      for (Object value : caseValues) {
        if (type.isInstance(value)) {
          timeline.add(type.cast(value));
        }
      }
    }
    return timeline;
  }

  @Override
  public <T> Optional<T> fetch(Parcel parcel, SeedCoordinate coordinate, Class<T> type) {
    final List<Object> caseValues =
        byParcel.getOrDefault(parcel, Map.of()).getOrDefault(coordinate.slug(), List.of());
    return caseValues.isEmpty()
        ? Optional.empty()
        : Optional.of(type.cast(caseValues.get(caseValues.size() - 1)));
  }

  @Override
  public <T> Optional<T> withdraw(Parcel parcel, SeedCoordinate coordinate, Class<T> type) {
    final Optional<T> current = fetch(parcel, coordinate, type);
    Optional.ofNullable(byParcel.get(parcel)).ifPresent(cases -> cases.remove(coordinate.slug()));
    return current;
  }

  @Override
  public List<Parcel> neighbours(Parcel parcel) {
    return List.of(parcel);
  }
}
