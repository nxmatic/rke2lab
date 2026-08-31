package io.seedmatic.rke2lab.host.runtime;

import io.seedmatic.rke2lab.seed.broker.port.SecretsGateway;
import java.util.List;
import java.util.Optional;

/**
 * A {@link SecretsGateway} that returns the FIRST non-empty result across an ordered chain of
 * delegates. Lets the host compose several sources behind the single published seam: e.g. {@link
 * TailscaleOauthClientGateway} (serves the {@code tailscale} block from ndh's provisioned OAuth
 * client) ahead of {@link DotSecretsGateway} (serves every other block from rke2lab {@code
 * .secrets}). Consumers keep one gateway view.
 */
public final class ChainedSecretsGateway implements SecretsGateway {

  private final List<SecretsGateway> delegates;

  public ChainedSecretsGateway(final List<SecretsGateway> delegates) {
    this.delegates = List.copyOf(delegates);
  }

  @Override
  public Optional<String> read(final String dottedPath) {
    for (final SecretsGateway delegate : delegates) {
      final Optional<String> value = delegate.read(dottedPath);
      if (value.isPresent()) {
        return value;
      }
    }
    return Optional.empty();
  }
}
