package io.seedmatic.rke2lab.manifests.bdd.versions;

import com.fasterxml.jackson.databind.JsonNode;
import io.seedmatic.rke2lab.manifests.contract.ManifestVersionsBumpInput;
import io.seedmatic.rke2lab.seed.broker.codec.AmendmentBinder;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.seedmatic.rke2lab.seed.broker.port.AmendCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.Cellar;
import io.seedmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.seedmatic.rke2lab.seed.broker.port.SeedHandler;
import java.util.LinkedHashMap;
import java.util.Map;
import org.osgi.service.component.annotations.Component;

/**
 * The version-bump amend reflector — the twin of {@code WorktreeAmendReflector}, serving {@link
 * AmendCoordinate}{@code (manifests-versions)} so the flat host can fill the bump {@link
 * ManifestVersionsBumpInput.BumpFacet} (level / apply / component) without naming a manifests type:
 * it hands in opaque JSON on the {@code FACET} role and the binder places it on the {@code facet}
 * field. Vocabulary reconciliation lives at the door, never in the runbook handler.
 */
@Component(service = SeedHandler.class)
public final class VersionsAmendReflector implements SeedHandler {

  private static final String DOMAIN = "manifests-versions";

  private final SeedCodec codec = new SeedCodec();
  private final AmendmentBinder binder = new AmendmentBinder();

  @Override
  public SeedCoordinate serves() {
    return new AmendCoordinate(DOMAIN);
  }

  @Override
  public SeedEnvelope handle(Cellar cellar, SeedEnvelope seed) {
    final Map<String, JsonNode> roleValues = roleValues(codec.decode(seed.payload()));
    final JsonNode amended = binder.bind(ManifestVersionsBumpInput.class, roleValues);
    return new SeedEnvelope(DOMAIN, seed.coordinate(), codec.encode(amended));
  }

  private static Map<String, JsonNode> roleValues(JsonNode payload) {
    final Map<String, JsonNode> values = new LinkedHashMap<>();
    payload.properties().forEach(entry -> values.put(entry.getKey(), entry.getValue()));
    return values;
  }
}
