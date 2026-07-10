package io.nxmatic.rke2lab.doctor.internal;

import io.nxmatic.rke2lab.doctor.records.Consultation;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.Rootstock;
import io.nxmatic.rke2lab.seed.broker.port.Scion;
import io.nxmatic.rke2lab.seed.broker.port.SeedContract;
import io.nxmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.nxmatic.rke2lab.seed.broker.port.SeedHandler;
import io.nxmatic.rke2lab.seed.broker.port.SplitCoordinate;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.osgi.service.component.annotations.Component;

/**
 * Doctor's contribution of the split verb: it serves {@link SplitCoordinate}{@code ("doctor")} so
 * the write frontier — holding only an opaque {@link SeedEnvelope} — can ask "split this envelope"
 * and get the {@link Scion}s grouped under their {@link Rootstock}, holding no doctor class and no
 * storage-slot name. The reflection of the marked components happens HERE, OSGi-side, where the
 * wire-record's class lives (doctor's realm); the frontier only asks and nests. This is the runtime
 * twin of the build-time schema projection — the mechanism the whole opacity turn rests on.
 *
 * <p>The seed's coordinate selects the wire-record to reflect (doctor owns the mapping — a single
 * source, no hardcoded field list): each split-bearing record is registered by its {@link
 * SeedContract} slug, its {@code @Scion}-marked components read via reflection into the flat
 * sub-trees the host copies verbatim, grouped under the value of its single {@code @Rootstock}
 * component (the receiver identity the frontier nests each scion under). An envelope with no
 * populated scion files nothing, so a symptomless consult leaves the stack contract byte-identical.
 */
@Component(service = SeedHandler.class)
public final class DoctorSplitReflector implements SeedHandler {

  private static final String DOMAIN = "doctor";

  /**
   * The doctor wire-records that bear scions, indexed by their {@code @SeedContract} slug. Doctor
   * owns this map: adding a split-bearing coordinate means adding its record here, one place.
   */
  private static final Map<String, Class<?>> SPLIT_BEARERS = index(Consultation.class);

  private final SeedCodec codec = new SeedCodec();

  @Override
  public SeedCoordinate serves() {
    return new SplitCoordinate(DOMAIN);
  }

  @Override
  public SeedEnvelope handle(SeedEnvelope seed) {
    final Class<?> bearer = SPLIT_BEARERS.get(seed.coordinate());
    if (bearer == null) {
      // A seed whose coordinate carries no scions splits to nothing — the frontier nests an empty
      // map.
      return SeedEnvelope.of(new SplitCoordinate(DOMAIN), codec.encode(Map.of()));
    }
    final Object decoded = codec.decode(seed, bearer);
    final Map<String, Object> split = splitByRootstock(bearer, decoded);
    return SeedEnvelope.of(new SplitCoordinate(DOMAIN), codec.encode(split));
  }

  /**
   * Group the populated {@code @Scion} components under the value of the single {@code @Rootstock}
   * component, keyed by each scion's ROLE (its {@code @Scion.value()}, a neutral gardening part —
   * {@code fruit}, {@code sowing}): {@code { rootstockValue -> { role -> scionValue } } }. An empty
   * scion is omitted (nothing to file); if every scion is empty the split is an empty map (the
   * frontier nests nothing), so the rootstock key is not emitted for a symptomless consult.
   */
  private Map<String, Object> splitByRootstock(Class<?> bearer, Object decoded) {
    final LinkedHashMap<String, Object> scions = new LinkedHashMap<>();
    String rootstock = null;
    for (RecordComponent component : bearer.getRecordComponents()) {
      if (component.isAnnotationPresent(Rootstock.class)) {
        rootstock = String.valueOf(readComponent(component, decoded));
        continue;
      }
      final Scion scion = component.getAnnotation(Scion.class);
      if (scion == null) {
        continue;
      }
      final Object value = readComponent(component, decoded);
      if (!isEmpty(value)) {
        scions.put(scion.value(), value);
      }
    }
    if (scions.isEmpty()) {
      return Map.of();
    }
    if (rootstock == null) {
      throw new IllegalStateException(
          bearer + " bears scions but declares no @Rootstock to group them under");
    }
    return Map.of(rootstock, scions);
  }

  private static Object readComponent(RecordComponent component, Object record) {
    try {
      return component.getAccessor().invoke(record);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(
          "cannot read component " + component.getName() + " of " + record.getClass(), e);
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
        throw new IllegalStateException(bearer + " bears scions but declares no @SeedContract");
      }
      byCoordinate.put(contract.value(), bearer);
    }
    return Map.copyOf(byCoordinate);
  }
}
