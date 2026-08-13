package io.seedmatic.rke2lab.pulumi.edge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The central component that collects {@link PulumiOutputContributor}s and assembles the final
 * Pulumi stack-output map. Contributors register via {@link #add(PulumiOutputContributor)}; {@link
 * #assemble()} projects each one (the single flatten point) and merges the results, guaranteeing:
 *
 * <ul>
 *   <li>no key collision — two contributors emitting the same output key is a named failure, not a
 *       silent overwrite;
 *   <li>deterministic order — contributions merge in registration order, so the output map is
 *       stable across runs (no phantom Pulumi diffs).
 * </ul>
 *
 * <p>This replaces the omniscient output builder that pulled each summary by name: the registry
 * knows nothing about individual outputs, only the contract. Adding an output never reopens this
 * class.
 */
public final class PulumiOutputRegistry {

  private final List<PulumiOutputContributor> contributors = new ArrayList<>();

  public PulumiOutputRegistry add(PulumiOutputContributor contributor) {
    contributors.add(contributor);
    return this;
  }

  /**
   * Projects and merges every registered contribution into the final stack-output map.
   *
   * @throws IllegalStateException if two contributors emit the same output key (the message names
   *     both offending contributors)
   */
  public Map<String, Object> assemble() {
    final Map<String, Object> outputs = new LinkedHashMap<>();
    final Map<String, String> keyOwner = new LinkedHashMap<>();

    for (PulumiOutputContributor contributor : contributors) {
      contributor
          .contribute()
          .forEach(
              (key, value) -> {
                final String previousOwner = keyOwner.putIfAbsent(key, contributor.namespace());
                Optional.ofNullable(previousOwner)
                    .ifPresent(
                        owner -> {
                          throw new IllegalStateException(
                              "output key '"
                                  + key
                                  + "' contributed by both '"
                                  + owner
                                  + "' and '"
                                  + contributor.namespace()
                                  + "'");
                        });
                outputs.put(key, value);
              });
    }

    return outputs;
  }
}
