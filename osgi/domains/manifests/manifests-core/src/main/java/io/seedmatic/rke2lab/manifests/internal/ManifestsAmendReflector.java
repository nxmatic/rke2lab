package io.seedmatic.rke2lab.manifests.internal;

import com.fasterxml.jackson.databind.JsonNode;
import io.seedmatic.rke2lab.manifests.contract.ManifestsCoordinate;
import io.seedmatic.rke2lab.manifests.contract.ManifestsRunbookInput;
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
 * Manifests' contribution of the amend verb: it serves {@link AmendCoordinate}{@code ("manifests")}
 * so a sower — holding a value under a NEUTRAL role ({@code soil}) — can fill the manifests runbook
 * input without naming the field ({@code materializationRoot}). The reconciling twin of {@link
 * ManifestsShapeReflector}: Shape projects the input's schema (what form?), Amend binds a {@code
 * {role → value}} map onto it (fill it by role). Both reflect OSGi-side, where the wire-record's
 * class lives; the caller names only a role.
 *
 * <p>The seed's payload is a {@code {role → value}} map; the reflector hands the gathered + offered
 * roles to the foundation {@link AmendmentBinder}, which places each role's value in its
 * {@code @Amendment} field and FAILS if the mandatory FACET ({@code facets}) was not offered by any
 * sower (the door supplies no default — {@code SOIL} and {@code IDENTITY} are {@code Optional} and
 * may be absent). The amended node is returned (opaque) under the {@code runbook} coordinate —
 * ready to sow at {@link io.seedmatic.rke2lab.seed.broker.port.RunbookCoordinate}. So the
 * vocabulary reconciliation lives at the door (the broker routes the amend verb here), never in the
 * runbook handler.
 */
@Component(service = SeedHandler.class)
public final class ManifestsAmendReflector implements SeedHandler {

  private static final String DOMAIN = ManifestsCoordinate.DOMAIN;

  /**
   * The manifests input wire-records that bear amendments, indexed by {@code @SeedContract} slug.
   */
  private static final Map<String, Class<?>> AMEND_BEARERS = index(ManifestsRunbookInput.class);

  private final SeedCodec codec = new SeedCodec();
  private final AmendmentBinder binder = new AmendmentBinder();
  private final AmendmentAssembler assembler;

  @Activate
  public ManifestsAmendReflector(@Reference AmendmentAssembler assembler) {
    this.assembler = assembler;
  }

  @Override
  public SeedCoordinate serves() {
    return ManifestsCoordinate.AMEND;
  }

  @Override
  public SeedEnvelope handle(Cellar cellar, SeedEnvelope seed) {
    final Class<?> bearer = AMEND_BEARERS.get(seed.coordinate());
    if (bearer == null) {
      throw new IllegalArgumentException(
          "manifests amends no input for coordinate '" + seed.coordinate() + "'");
    }
    // Ambient roles (e.g. FACET, the operator config only the host holds) gathered at the door and
    // merged UNDER the roles the sower offered — a sower's per-consult value (SOIL, WORKTREE) wins
    // over an ambient contribution on the same role. No sower carries a role it does not own.
    final Map<String, JsonNode> roleValues = new LinkedHashMap<>();
    assembler
        .gather(ManifestsCoordinate.AMEND)
        .forEach((role, json) -> roleValues.put(role, codec.decode(json)));
    roleValues.putAll(roleValues(codec.decode(seed.payload())));
    final JsonNode amended = binder.bind(bearer, roleValues);
    // Returned under the runbook coordinate: the amended payload is ready to sow at
    // RunbookCoordinate("manifests"), the coordinate this input is the @SeedContract for.
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
