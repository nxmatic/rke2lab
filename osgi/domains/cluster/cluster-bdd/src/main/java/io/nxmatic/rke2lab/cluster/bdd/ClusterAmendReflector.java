package io.nxmatic.rke2lab.cluster.bdd;

import com.fasterxml.jackson.databind.JsonNode;
import io.nxmatic.rke2lab.seed.broker.codec.AmendmentBinder;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.AmendCoordinate;
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
 * The cluster domain's contribution of the amend verb — the twin of {@code SystemdAmendReflector}:
 * it serves {@link AmendCoordinate}{@code ("cluster")} so the host's {@code FACET} (the published
 * kubeconfig path) fills the readiness runbook input without the host naming a cluster type.
 *
 * <p>The seed's payload is a {@code {role → value}} map; the reflector serializes {@link
 * ReadinessInput#defaults() defaults}, gathers the ambient FACET at the door, merges the sower's
 * per-consult roles on top, and hands both to the foundation {@link AmendmentBinder}, which places
 * the FACET role's value on the input's {@code access} field. The amended node returns under the
 * {@code runbook} coordinate — ready to sow at {@code RunbookCoordinate("cluster")}.
 */
@Component(service = SeedHandler.class)
public final class ClusterAmendReflector implements SeedHandler {

  private static final String DOMAIN = "cluster";

  /** The cluster input wire-records that bear amendments, indexed by {@code @SeedContract} slug. */
  private static final Map<String, Class<?>> AMEND_BEARERS = index(ReadinessInput.class);

  private final SeedCodec codec = new SeedCodec();
  private final AmendmentBinder binder = new AmendmentBinder();
  private final AmendmentAssembler assembler;

  @Activate
  public ClusterAmendReflector(@Reference AmendmentAssembler assembler) {
    this.assembler = assembler;
  }

  @Override
  public SeedCoordinate serves() {
    return new AmendCoordinate(DOMAIN);
  }

  @Override
  public SeedEnvelope handle(Cellar cellar, SeedEnvelope seed) {
    final Class<?> bearer = AMEND_BEARERS.get(seed.coordinate());
    if (bearer == null) {
      throw new IllegalArgumentException(
          "cluster amends no input for coordinate '" + seed.coordinate() + "'");
    }
    // Ambient roles (the FACET — the published kubeconfig path only the host holds) gathered at the
    // door and merged UNDER any per-consult roles the sower offered: a per-consult value wins.
    final Map<String, JsonNode> roleValues = new LinkedHashMap<>();
    assembler
        .gather(new AmendCoordinate(DOMAIN))
        .forEach((role, json) -> roleValues.put(role, codec.decode(json)));
    roleValues.putAll(roleValues(codec.decode(seed.payload())));
    final JsonNode defaults = codec.decode(codec.encode(ReadinessInput.defaults()));
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
