package io.seedmatic.rke2lab.seed.broker.testkit;

import io.seedmatic.rke2lab.seed.broker.port.Cellar;
import io.seedmatic.rke2lab.seed.broker.port.Parcel;
import io.seedmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.Sensitivity;
import java.util.List;
import java.util.Optional;

/**
 * A {@link Cellar} that THROWS on every access — the honest stand-in a test passes to a {@code
 * SeedHandler.handle} whose handler must NOT touch the cellar. The reflectors (Amend/Shape/Split/
 * Intervention/Readiness) TRANSFORM a seed and ignore the transaction by contract; their unit tests
 * still have to pass a {@code Cellar} (the seam takes one, never null). Passing this makes the
 * contract executable: if a reflector ever reads the cellar, the test fails LOUD here rather than
 * NPE-ing on a null or silently reading an empty double that would hide the breach.
 *
 * <p>A {@code *RunbookHandler} (which DOES use the cellar) is never tested with this — it gets a
 * real {@code ScenarioCellar}.
 */
public final class RefusingCellar implements Cellar {

  public static final Cellar INSTANCE = new RefusingCellar();

  private RefusingCellar() {}

  private static AssertionError refuse() {
    return new AssertionError(
        "this handler must not touch the cellar — it TRANSFORMS the seed and ignores the "
            + "transaction (a reflector). A cellar access here is a contract breach.");
  }

  @Override
  public <T> void store(
      Parcel parcel, SeedCoordinate coordinate, T value, Sensitivity sensitivity) {
    throw refuse();
  }

  @Override
  public <T> List<T> fetch(Parcel parcel, Class<T> type) {
    throw refuse();
  }

  @Override
  public <T> Optional<T> fetch(Parcel parcel, SeedCoordinate coordinate, Class<T> type) {
    throw refuse();
  }

  @Override
  public <T> Optional<T> withdraw(Parcel parcel, SeedCoordinate coordinate, Class<T> type) {
    throw refuse();
  }

  @Override
  public List<Parcel> neighbours(Parcel parcel) {
    throw refuse();
  }
}
