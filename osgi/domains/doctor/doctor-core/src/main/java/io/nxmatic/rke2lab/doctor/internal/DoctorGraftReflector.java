package io.nxmatic.rke2lab.doctor.internal;

import io.nxmatic.rke2lab.doctor.records.Consultation;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.Graft;
import io.nxmatic.rke2lab.seed.broker.port.GraftCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.SeedContract;
import io.nxmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.nxmatic.rke2lab.seed.broker.port.SeedHandler;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.osgi.service.component.annotations.Component;

/**
 * Doctor's contribution of the introspection verb: it serves {@link GraftCoordinate}{@code
 * ("doctor")} so the write frontier — holding only an opaque {@link SeedEnvelope} — can ask "hand
 * me this seed's grafts" and file them, holding no doctor class and no storage-slot name. The
 * reflection of {@link Graft} components happens HERE, OSGi-side, where the wire-record's class
 * lives (doctor's realm); the frontier only asks and affixes. This is the runtime twin of the
 * build-time schema projection — the mechanism the whole opacity turn rests on.
 *
 * <p>The seed's coordinate selects the wire-record to reflect (doctor owns the mapping — a single
 * source, no hardcoded field list): each graft-bearing record is registered by its {@link
 * SeedContract} slug, its {@code @Graft}-marked components read via {@code SeedCodec.fromMap} into
 * the flat sub-tree the host copies verbatim. An empty graft is omitted, so a symptomless consult
 * files nothing and the stack contract stays byte-identical.
 */
@Component(service = SeedHandler.class)
public final class DoctorGraftReflector implements SeedHandler {

  private static final String DOMAIN = "doctor";

  /**
   * The doctor wire-records that bear grafts, indexed by their {@code @SeedContract} slug. Doctor
   * owns this map: adding a graft-bearing coordinate means adding its record here, one place.
   */
  private static final Map<String, Class<?>> GRAFT_BEARERS = index(Consultation.class);

  private final SeedCodec codec = new SeedCodec();

  @Override
  public SeedCoordinate serves() {
    return new GraftCoordinate(DOMAIN);
  }

  @Override
  public SeedEnvelope handle(SeedEnvelope seed) {
    final Class<?> bearer = GRAFT_BEARERS.get(seed.coordinate());
    if (bearer == null) {
      // A seed whose coordinate carries no grafts files nothing — the frontier affixes an empty
      // map.
      return SeedEnvelope.of(new GraftCoordinate(DOMAIN), codec.encode(Map.of()));
    }
    final Object decoded = codec.decode(seed, bearer);
    final Map<String, Object> grafts = reflectGrafts(bearer, decoded);
    return SeedEnvelope.of(new GraftCoordinate(DOMAIN), codec.encode(grafts));
  }

  /** Read each {@code @Graft} component's value; omit an empty one (nothing to file). */
  private Map<String, Object> reflectGrafts(Class<?> bearer, Object decoded) {
    final LinkedHashMap<String, Object> grafts = new LinkedHashMap<>();
    for (RecordComponent component : bearer.getRecordComponents()) {
      if (!component.isAnnotationPresent(Graft.class)) {
        continue;
      }
      final Object value = readComponent(component, decoded);
      if (!isEmpty(value)) {
        grafts.put(component.getName(), value);
      }
    }
    return grafts;
  }

  private static Object readComponent(RecordComponent component, Object record) {
    try {
      return component.getAccessor().invoke(record);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(
          "cannot read graft component " + component.getName() + " of " + record.getClass(), e);
    }
  }

  private static boolean isEmpty(Object value) {
    return value == null
        || (value instanceof Map<?, ?> map && map.isEmpty())
        || (value instanceof List<?> list && list.isEmpty());
  }

  private static Map<String, Class<?>> index(Class<?>... bearers) {
    final LinkedHashMap<String, Class<?>> byCoordinate = new LinkedHashMap<>();
    for (Class<?> bearer : bearers) {
      final SeedContract contract = bearer.getAnnotation(SeedContract.class);
      if (contract == null) {
        throw new IllegalStateException(bearer + " bears grafts but declares no @SeedContract");
      }
      byCoordinate.put(contract.value(), bearer);
    }
    return Map.copyOf(byCoordinate);
  }
}
