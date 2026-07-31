package io.nxmatic.rke2lab.netplan.bdd;

import com.fasterxml.jackson.databind.JsonNode;
import io.nxmatic.rke2lab.netplan.contract.NetplanCoordinate;
import io.nxmatic.rke2lab.netplan.contract.NetplanRunbookInput;
import io.nxmatic.rke2lab.seed.broker.codec.AmendmentBinder;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.AmendmentAssembler;
import io.nxmatic.rke2lab.seed.broker.port.Cellar;
import io.nxmatic.rke2lab.seed.broker.port.SeedContract;
import io.nxmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.nxmatic.rke2lab.seed.broker.port.SeedHandler;
import java.util.LinkedHashMap;
import java.util.Map;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Netplan's contribution of the amend verb: it serves {@code AmendCoordinate("netplan")} so a sower
 * — holding a value under a NEUTRAL role ({@code soil}) — can fill the netplan runbook input
 * without naming the field ({@code materializationRoot}). Mirrors {@code ManifestsAmendReflector}:
 * it serialises {@link NetplanRunbookInput#defaults()} and hands them + the sower's roles to the
 * foundation {@link AmendmentBinder}, which reads the {@code @Amendment} components and places each
 * role's value in its field. The amended node is returned under the {@code runbook} coordinate,
 * ready to sow at {@link NetplanCoordinate#RUNBOOK}. So the vocabulary reconciliation lives at the
 * door, never in the runbook handler.
 */
@Component(service = SeedHandler.class)
public final class NetplanAmendReflector implements SeedHandler {

  private static final String DOMAIN = NetplanCoordinate.DOMAIN;

  /** The netplan input wire-records that bear amendments, indexed by {@code @SeedContract} slug. */
  private static final Map<String, Class<?>> AMEND_BEARERS = index(NetplanRunbookInput.class);

  private final SeedCodec codec = new SeedCodec();
  private final AmendmentBinder binder = new AmendmentBinder();
  private final AmendmentAssembler assembler;

  @Activate
  public NetplanAmendReflector(@Reference AmendmentAssembler assembler) {
    this.assembler = assembler;
  }

  @Override
  public SeedCoordinate serves() {
    return NetplanCoordinate.AMEND;
  }

  @Override
  public SeedEnvelope handle(Cellar cellar, SeedEnvelope seed) {
    final Class<?> bearer = AMEND_BEARERS.get(seed.coordinate());
    if (bearer == null) {
      throw new IllegalArgumentException(
          "netplan amends no input for coordinate '" + seed.coordinate() + "'");
    }
    // Ambient roles gathered at the door and merged UNDER the roles the sower offered — a sower's
    // per-consult value (SOIL) wins over an ambient contribution on the same role.
    final Map<String, JsonNode> roleValues = new LinkedHashMap<>();
    assembler
        .gather(NetplanCoordinate.AMEND)
        .forEach((role, json) -> roleValues.put(role, codec.decode(json)));
    roleValues.putAll(roleValues(codec.decode(seed.payload())));
    final JsonNode defaults = codec.decode(codec.encode(NetplanRunbookInput.defaults()));
    final JsonNode amended = binder.bind(bearer, defaults, roleValues);
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
