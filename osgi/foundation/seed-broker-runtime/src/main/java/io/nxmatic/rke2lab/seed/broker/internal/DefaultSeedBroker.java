package io.nxmatic.rke2lab.seed.broker.internal;

import io.nxmatic.rke2lab.seed.broker.port.SeedBroker;
import io.nxmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.nxmatic.rke2lab.seed.broker.port.SeedHandler;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;

/**
 * The one door: collects every {@link SeedHandler} published OSGi-side (by Declarative Services)
 * and routes a sown seed to the handler that {@link SeedHandler#serves serves} the WANTED
 * coordinate. It subsumes the former per-crossing service interfaces — the caller resolves ONE
 * {@code SeedBroker} and names what it wants to reap, instead of resolving {@code
 * ReadinessAuthority} then {@code InterventionIntake} etc. Published as the {@link SeedBroker} seam
 * so the flat host addresses it across the boundary.
 *
 * <p>The roster arrives by {@code @Reference(MULTIPLE)}: a domain contributes a {@code SeedHandler}
 * {@code @Component} per coordinate it grows, and the broker indexes them by {@link
 * SeedHandler#serves}. Two handlers claiming one coordinate is a wiring bug the build must not ship
 * — the index build fails loudly rather than silently dropping one.
 */
@Component(service = SeedBroker.class)
public final class DefaultSeedBroker implements SeedBroker {

  private final Map<SeedCoordinate, SeedHandler> handlers;

  @Activate
  public DefaultSeedBroker(
      @Reference(cardinality = ReferenceCardinality.MULTIPLE) List<SeedHandler> handlers) {
    this.handlers =
        handlers.stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    SeedHandler::serves,
                    Function.identity(),
                    (a, b) -> {
                      throw new IllegalStateException(
                          "two SeedHandlers serve the same coordinate "
                              + a.serves()
                              + ": "
                              + a.getClass().getName()
                              + " and "
                              + b.getClass().getName());
                    }));
  }

  @Override
  public SeedEnvelope sow(SeedCoordinate wanted, SeedEnvelope seed) {
    final SeedHandler handler = handlers.get(wanted);
    if (handler == null) {
      throw new IllegalStateException(
          "no SeedHandler serves coordinate " + wanted + " (a coordinate with no grower)");
    }
    return handler.handle(seed);
  }
}
