package io.nxmatic.rke2lab.systemd.bdd;

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
import io.nxmatic.rke2lab.systemd.contract.SystemdRunbookInput;
import java.util.LinkedHashMap;
import java.util.Map;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Systemd's contribution of the amend verb — the twin of {@code IncusAmendReflector}: it serves
 * {@link AmendCoordinate}{@code ("systemd")} so the host's ambient cluster/node {@code FACET} fills
 * the systemd runbook input without the host naming a systemd/netplan type. The scenario derives
 * the network blueprint from that identity and composes its probe endpoint (§ netplan the single
 * source of network-derived names; ports stay service-side).
 *
 * <p>The seed's payload is a {@code {role → value}} map; the reflector gathers the ambient FACET at
 * the door and hands it to the foundation {@link AmendmentBinder}, which places the FACET role's
 * value on the input's {@code identity} field ({@code identity} is {@code Optional} — absent binds
 * empty, no door default). The amended node returns under the {@code runbook} coordinate — ready to
 * sow at {@code RunbookCoordinate("systemd")}, so the vocabulary reconciliation lives at the door.
 */
@Component(service = SeedHandler.class)
public final class SystemdAmendReflector implements SeedHandler {

  private static final String DOMAIN = "systemd";

  /** The systemd input wire-records that bear amendments, indexed by {@code @SeedContract} slug. */
  private static final Map<String, Class<?>> AMEND_BEARERS = index(SystemdRunbookInput.class);

  private final SeedCodec codec = new SeedCodec();
  private final AmendmentBinder binder = new AmendmentBinder();
  private final AmendmentAssembler assembler;

  @Activate
  public SystemdAmendReflector(@Reference AmendmentAssembler assembler) {
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
          "systemd amends no input for coordinate '" + seed.coordinate() + "'");
    }
    // Ambient roles (the FACET — the stable cluster/node identity only the host holds) gathered at
    // the door and merged UNDER any per-consult roles the sower offered: a per-consult value wins.
    final Map<String, JsonNode> roleValues = new LinkedHashMap<>();
    assembler
        .gather(new AmendCoordinate(DOMAIN))
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
