package io.nxmatic.rke2lab.worktree.bdd;

import com.fasterxml.jackson.databind.JsonNode;
import io.nxmatic.rke2lab.seed.broker.codec.AmendmentBinder;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.AmendCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.Cellar;
import io.nxmatic.rke2lab.seed.broker.port.SeedContract;
import io.nxmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.nxmatic.rke2lab.seed.broker.port.SeedHandler;
import io.nxmatic.rke2lab.worktree.WorktreeRunbookInput;
import java.util.LinkedHashMap;
import java.util.Map;
import org.osgi.service.component.annotations.Component;

/**
 * The worktree domain's contribution of the amend verb — the twin of {@code IncusAmendReflector}:
 * it serves {@link AmendCoordinate}{@code ("worktree")} so the host can fill the worktree runbook
 * input's {@code FACET} (the entry-gate {@link io.nxmatic.rke2lab.worktree.GatePolicy}) without
 * naming any worktree type — it hands in opaque JSON on the {@code FACET} role and the binder
 * places it in the field.
 *
 * <p>The seed's payload is a {@code {role → value}} map; the reflector hands the offered roles to
 * the foundation {@link AmendmentBinder}, which places each role's value in its {@code @Amendment}
 * field ({@code gate} is {@code Optional} — absent binds empty, no door default). The amended node
 * is returned (opaque) under the {@code worktree} coordinate — ready to sow at {@link
 * io.nxmatic.rke2lab.seed.broker.port.RunbookCoordinate}. So the vocabulary reconciliation lives at
 * the door, never in the runbook handler.
 */
@Component(service = SeedHandler.class)
public final class WorktreeAmendReflector implements SeedHandler {

  private static final String DOMAIN = "worktree";

  /**
   * The worktree input wire-records that bear amendments, indexed by {@code @SeedContract} slug.
   */
  private static final Map<String, Class<?>> AMEND_BEARERS = index(WorktreeRunbookInput.class);

  private final SeedCodec codec = new SeedCodec();
  private final AmendmentBinder binder = new AmendmentBinder();

  @Override
  public SeedCoordinate serves() {
    return new AmendCoordinate(DOMAIN);
  }

  @Override
  public SeedEnvelope handle(Cellar cellar, SeedEnvelope seed) {
    final Class<?> bearer = AMEND_BEARERS.get(seed.coordinate());
    if (bearer == null) {
      throw new IllegalArgumentException(
          "worktree amends no input for coordinate '" + seed.coordinate() + "'");
    }
    final Map<String, JsonNode> roleValues = roleValues(codec.decode(seed.payload()));
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
