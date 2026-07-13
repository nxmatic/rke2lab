package io.nxmatic.rke2lab.manifests.internal;

import com.fasterxml.jackson.databind.JsonNode;
import io.nxmatic.rke2lab.manifests.contract.ManifestsRunbookInput;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.SeedContract;
import io.nxmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.nxmatic.rke2lab.seed.broker.port.SeedHandler;
import io.nxmatic.rke2lab.seed.broker.port.ShapeCoordinate;
import io.nxmatic.rke2lab.seed.broker.shape.RecordSchemaProjector;
import java.util.LinkedHashMap;
import java.util.Map;
import org.osgi.service.component.annotations.Component;

/**
 * Manifests' contribution of the shape verb: it serves {@link ShapeCoordinate}{@code ("manifests")}
 * so a sower — holding only a soil name — can ask "what payload does the manifests runbook want?"
 * and get the JSON Schema of the runbook-input wire-record, holding no manifests class. The AMONT
 * twin of {@code DoctorSplitReflector}: Split reflects a reaped record's {@code @Scion} components
 * (aval), this projects an input record's schema (amont). Both reflect OSGi-side, where the
 * wire-record's class lives; the frontier only asks and reaps an opaque String.
 *
 * <p>The seed's coordinate selects the wire-record to describe (manifests owns the mapping — a
 * single source, no hardcoded field list): each shape-bearing record is registered by its {@link
 * SeedContract} slug, and its schema projected via the {@link RecordSchemaProjector} (victools,
 * Draft 2020-12). This is the runtime half of the build-time schema projection — the mechanism the
 * introspection turn rests on (see docs/architecture/osgi/seed-broker-spec.adoc § introspection).
 */
@Component(service = SeedHandler.class)
public final class ManifestsShapeReflector implements SeedHandler {

  private static final String DOMAIN = "manifests";

  /**
   * The manifests wire-records a sower can ask the shape of, indexed by their {@code @SeedContract}
   * slug. Manifests owns this map: a new shape-bearing coordinate means adding its record here, one
   * place — the twin of {@code DoctorSplitReflector.SPLIT_BEARERS}.
   */
  private static final Map<String, Class<?>> SHAPE_BEARERS = index(ManifestsRunbookInput.class);

  private final SeedCodec codec = new SeedCodec();
  private final RecordSchemaProjector projector = new RecordSchemaProjector();

  @Override
  public SeedCoordinate serves() {
    return new ShapeCoordinate(DOMAIN);
  }

  @Override
  public SeedEnvelope handle(SeedEnvelope seed) {
    final Class<?> bearer = SHAPE_BEARERS.get(seed.coordinate());
    if (bearer == null) {
      throw new IllegalArgumentException(
          "manifests describes no shape for coordinate '" + seed.coordinate() + "'");
    }
    final JsonNode schema = projector.project(bearer);
    return SeedEnvelope.of(new ShapeCoordinate(DOMAIN), codec.encode(schema));
  }

  private static Map<String, Class<?>> index(Class<?>... bearers) {
    final LinkedHashMap<String, Class<?>> byCoordinate = new LinkedHashMap<>();
    for (Class<?> bearer : bearers) {
      final SeedContract contract = bearer.getAnnotation(SeedContract.class);
      if (contract == null) {
        throw new IllegalStateException(bearer + " bears a shape but declares no @SeedContract");
      }
      byCoordinate.put(contract.value(), bearer);
    }
    return Map.copyOf(byCoordinate);
  }
}
