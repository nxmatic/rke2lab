package io.seedmatic.rke2lab.auth.edge;

import io.seedmatic.rke2lab.auth.contract.GithubWriterTokenMint;
import io.seedmatic.rke2lab.ghapp.contract.GithubAppCredentials;
import io.seedmatic.rke2lab.ghapp.contract.GithubAppMinter;
import io.seedmatic.rke2lab.ghapp.contract.TokenScope;
import java.util.Optional;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * The realised on-demand push-token edge: mints a FRESH {@code WRITER} ({@code contents:write})
 * installation token from the App credentials the consumer hands it, delegating to the ghapp {@link
 * GithubAppMinter} (the JWT-signing GitHub contact). It holds the mint verb behind auth-contract's
 * pure-JDK {@link GithubWriterTokenMint} so the consumer supplies the durable credential fields it
 * revealed from its own cellar case — without importing a single ghapp type. The auth domain is the
 * one place that delegates GitHub sourcing to ghapp; this is that seam, now on demand rather than a
 * durable seal.
 *
 * <p>The mandatory {@link Reference} to {@link GithubAppMinter} (a {@code cultivating}-gated
 * {@code @Component}) means this edge only activates when the minter is present, and it is itself
 * tagged {@code rke2lab.gardening=cultivating}: under a survey/preview frontier the consumer's
 * {@code @OsgiService} filter resolves it empty, so no token is fabricated and the push is skipped.
 */
@Component(service = GithubWriterTokenMint.class, property = "rke2lab.gardening=cultivating")
public final class GithubWriterTokenMintEdge implements GithubWriterTokenMint {

  private final GithubAppMinter minter;

  @Activate
  public GithubWriterTokenMintEdge(@Reference GithubAppMinter minter) {
    this.minter = minter;
  }

  @Override
  public Optional<String> mint(String appId, String installationId, String privateKeyPem) {
    return Optional.of(
        minter
            .mint(new GithubAppCredentials(appId, installationId, privateKeyPem), TokenScope.WRITER)
            .token());
  }
}
