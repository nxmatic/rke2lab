package io.nxmatic.rke2lab.bbox.core.internal;

import com.fasterxml.jackson.databind.JsonNode;
import io.nxmatic.rke2lab.bbox.contract.BboxCoordinate;
import io.nxmatic.rke2lab.bbox.contract.BboxRunbookInput;
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
 * Bbox's contribution of the amend verb: it serves {@link BboxCoordinate#AMEND} so the host —
 * holding the router contact under the NEUTRAL {@link
 * io.nxmatic.rke2lab.seed.broker.port.Amendment#FACET} role — can fill the bbox runbook input
 * without naming the field ({@code router}). The exact twin of {@code ManifestsAmendReflector}: it
 * gathers the ambient contributions at the door and binds them onto {@link
 * BboxRunbookInput#defaults()}, so the vocabulary reconciliation lives at the door, never in the
 * runbook handler.
 *
 * <p>The seed's payload is a {@code {role → value}} map; the reflector serializes this record's
 * {@code defaults()} and hands them + the roles to the foundation {@link AmendmentBinder}, which
 * reads the {@code @Amendment} components and places each role's value in its field. The amended
 * node is returned (opaque) under the {@code runbook} coordinate — ready to sow at {@link
 * BboxCoordinate#RUNBOOK}.
 */
@Component(service = SeedHandler.class)
public final class BboxAmendReflector implements SeedHandler {

  private static final String DOMAIN = "bbox";

  /** The bbox input wire-records that bear amendments, indexed by {@code @SeedContract} slug. */
  private static final Map<String, Class<?>> AMEND_BEARERS = index(BboxRunbookInput.class);

  private final SeedCodec codec = new SeedCodec();
  private final AmendmentBinder binder = new AmendmentBinder();
  private final AmendmentAssembler assembler;

  @Activate
  public BboxAmendReflector(@Reference AmendmentAssembler assembler) {
    this.assembler = assembler;
  }

  @Override
  public SeedCoordinate serves() {
    return BboxCoordinate.AMEND;
  }

  @Override
  public SeedEnvelope handle(Cellar cellar, SeedEnvelope seed) {
    final Class<?> bearer = AMEND_BEARERS.get(seed.coordinate());
    if (bearer == null) {
      throw new IllegalArgumentException(
          "bbox amends no input for coordinate '" + seed.coordinate() + "'");
    }
    // Ambient roles (the router FACET the host holds) gathered at the door and merged UNDER any
    // roles the sower offered — a per-consult value wins over an ambient contribution on the same
    // role. Bbox is sown with no per-consult role today, so only the ambient FACET fills it.
    final Map<String, JsonNode> roleValues = new LinkedHashMap<>();
    assembler
        .gather(BboxCoordinate.AMEND)
        .forEach((role, json) -> roleValues.put(role, codec.decode(json)));
    roleValues.putAll(roleValues(codec.decode(seed.payload())));
    final JsonNode defaults = codec.decode(codec.encode(BboxRunbookInput.defaults()));
    final JsonNode amended = binder.bind(bearer, defaults, roleValues);
    // Returned under the runbook coordinate: the amended payload is ready to sow at
    // RunbookCoordinate("bbox"), the coordinate this input is the @SeedContract for.
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
