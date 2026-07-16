package io.nxmatic.rke2lab.incus.bdd;

import com.fasterxml.jackson.databind.JsonNode;
import io.nxmatic.rke2lab.incus.contract.IncusRunbookInput;
import io.nxmatic.rke2lab.seed.broker.codec.AmendmentBinder;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.AmendCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.Cellar;
import io.nxmatic.rke2lab.seed.broker.port.SeedContract;
import io.nxmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.nxmatic.rke2lab.seed.broker.port.SeedHandler;
import java.util.LinkedHashMap;
import java.util.Map;
import org.osgi.service.component.annotations.Component;

/**
 * Incus' contribution of the amend verb — the twin of {@code ManifestsAmendReflector}: it serves
 * {@link AmendCoordinate}{@code ("incus")} so a sower holding a value under a NEUTRAL role ({@code
 * worktree}) can fill the incus runbook input without naming the field ({@code worktree}). The host
 * uses it to hand incus the flat provisioning scalars from its {@code BootstrapConfig}; the scion
 * reconstructs the topology, picks its slot, and derives the manifests SOIL itself (§
 * host-cellar-realisation, the whole topology computed OSGi-side).
 *
 * <p>The seed's payload is a {@code {role → value}} map; the reflector serializes {@link
 * IncusRunbookInput#defaults() defaults} and hands them + the roles to the foundation {@link
 * AmendmentBinder}, which reads the {@code @Amendment} components and places each role's value in
 * its field. The amended node is returned (opaque) under the {@code runbook} coordinate — ready to
 * sow at {@link io.nxmatic.rke2lab.seed.broker.port.RunbookCoordinate}. So the vocabulary
 * reconciliation lives at the door, never in the runbook handler.
 */
@Component(service = SeedHandler.class)
public final class IncusAmendReflector implements SeedHandler {

  private static final String DOMAIN = "incus";

  /** The incus input wire-records that bear amendments, indexed by {@code @SeedContract} slug. */
  private static final Map<String, Class<?>> AMEND_BEARERS = index(IncusRunbookInput.class);

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
          "incus amends no input for coordinate '" + seed.coordinate() + "'");
    }
    final Map<String, JsonNode> roleValues = roleValues(codec.decode(seed.payload()));
    final JsonNode defaults = codec.decode(codec.encode(IncusRunbookInput.defaults()));
    final JsonNode amended = binder.bind(bearer, defaults, roleValues);
    // Returned under the runbook coordinate: the amended payload is ready to sow at
    // RunbookCoordinate("incus"), the coordinate this input is the @SeedContract for.
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
