package io.seedmatic.rke2lab.dataplan.bdd;

import com.fasterxml.jackson.databind.JsonNode;
import io.seedmatic.rke2lab.dataplan.contract.DataplanCoordinate;
import io.seedmatic.rke2lab.dataplan.contract.DataplanRunbookInput;
import io.seedmatic.rke2lab.seed.broker.codec.AmendmentBinder;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.seedmatic.rke2lab.seed.broker.port.AmendmentAssembler;
import io.seedmatic.rke2lab.seed.broker.port.Cellar;
import io.seedmatic.rke2lab.seed.broker.port.SeedContract;
import io.seedmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.seedmatic.rke2lab.seed.broker.port.SeedHandler;
import java.util.LinkedHashMap;
import java.util.Map;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Dataplan's contribution of the amend verb: it serves {@code AmendCoordinate("dataplan")} so a
 * sower — holding a value under a NEUTRAL role ({@code soil}) — can fill the dataplan runbook input
 * without naming the field ({@code materializationRoot}). Mirrors {@code NetplanAmendReflector}: it
 * hands the gathered + offered roles to the foundation {@link AmendmentBinder}, which places each
 * role's value in its {@code @Amendment} field ({@code materializationRoot} is {@code Optional} —
 * absent binds empty, no door default). The amended node is returned under the {@code runbook}
 * coordinate, ready to sow at {@link DataplanCoordinate#RUNBOOK}. So the vocabulary reconciliation
 * lives at the door, never in the runbook handler.
 */
@Component(service = SeedHandler.class)
public final class DataplanAmendReflector implements SeedHandler {

  private static final String DOMAIN = DataplanCoordinate.DOMAIN;

  /**
   * The dataplan input wire-records that bear amendments, indexed by {@code @SeedContract} slug.
   */
  private static final Map<String, Class<?>> AMEND_BEARERS = index(DataplanRunbookInput.class);

  private final SeedCodec codec = new SeedCodec();
  private final AmendmentBinder binder = new AmendmentBinder();
  private final AmendmentAssembler assembler;

  @Activate
  public DataplanAmendReflector(@Reference AmendmentAssembler assembler) {
    this.assembler = assembler;
  }

  @Override
  public SeedCoordinate serves() {
    return DataplanCoordinate.AMEND;
  }

  @Override
  public SeedEnvelope handle(Cellar cellar, SeedEnvelope seed) {
    final Class<?> bearer = AMEND_BEARERS.get(seed.coordinate());
    if (bearer == null) {
      throw new IllegalArgumentException(
          "dataplan amends no input for coordinate '" + seed.coordinate() + "'");
    }
    // Ambient roles gathered at the door and merged UNDER the roles the sower offered — a sower's
    // per-consult value (SOIL) wins over an ambient contribution on the same role.
    final Map<String, JsonNode> roleValues = new LinkedHashMap<>();
    assembler
        .gather(DataplanCoordinate.AMEND)
        .forEach((role, json) -> roleValues.put(role, codec.decode(json)));
    roleValues.putAll(roleValues(codec.decode(seed.payload())));
    final JsonNode amended = binder.bind(bearer, roleValues);
    return new SeedEnvelope(DOMAIN, seed.coordinate(), codec.encode(amended));
  }

  private static Map<String, JsonNode> roleValues(JsonNode payload) {
    final Map<String, JsonNode> values = new LinkedHashMap<>();
    payload.properties().forEach(entry -> values.put(entry.getKey(), entry.getValue()));
    return values;
  }

  private static Map<String, Class<?>> index(Class<?>... bearers) {
    final LinkedHashMap<String, Class<?>> byCoordinate = new LinkedHashMap<>();
    for (Class<?> bearer : bearers) {
      final SeedContract contract = bearer.getAnnotation(SeedContract.class);
      if (contract == null) {
        throw new IllegalStateException(bearer + " bears amendments but declares no @SeedContract");
      }
      byCoordinate.put(contract.value(), bearer);
    }
    return Map.copyOf(byCoordinate);
  }
}
