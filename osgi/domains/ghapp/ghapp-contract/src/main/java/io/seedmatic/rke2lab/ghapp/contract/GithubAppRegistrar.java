package io.seedmatic.rke2lab.ghapp.contract;

import java.net.URI;
import java.util.function.Consumer;

/**
 * The ghapp domain's register verb: run GitHub's App-manifest flow to CREATE the one org-owned App
 * and capture its credentials — the irreducible one-human-click ceremony. The {@code ghapp-edge}
 * satisfies it by standing up a loopback HTTP endpoint (the manifest POST target + the
 * post-approval redirect catcher), exchanging the returned {@code code} at {@code POST
 * /app-manifests/&#123;code&#125;/conversions}, and returning the {@link GithubAppCredentials} the
 * conversion yields (id + installation id + the private key, delivered exactly once).
 *
 * <p>The orchestration is split so the SCENARIO drives both external contacts: {@link #register}
 * stands up the endpoint and, once it is listening, hands its URL to {@code onEndpointReady} — the
 * scenario passes the {@code OperatorNotifier} there, so the operator's browser pops toward the
 * endpoint. {@code register} then BLOCKS until GitHub's callback delivers the {@code code},
 * exchanges it, tears the endpoint down, and returns the resolved credentials.
 *
 * <p>Fail-fast, no silent fallback: it either returns credentials or throws / is interrupted (the
 * predictability invariant). It is a LIVE contact — it opens a socket and waits on a human — so the
 * edge is tagged {@code rke2lab.gardening=cultivating}: a survey/preview frontier filters it out
 * and the registration scenario PENDS instead of standing up the endpoint.
 */
public interface GithubAppRegistrar {

  /**
   * Stand up the loopback registration endpoint, hand its URL to {@code onEndpointReady} (the
   * signal to the operator), then block until GitHub's post-approval callback delivers the manifest
   * {@code code}, exchange it for the App's {@link GithubAppCredentials}, tear the endpoint down,
   * and return them.
   *
   * @param manifestJson the declared App manifest (permissions, redirect_url) POSTed to GitHub
   * @param onEndpointReady invoked once, with the endpoint URL, as soon as it is listening
   * @throws InterruptedException if the wait for the operator's approval is interrupted
   */
  GithubAppCredentials register(String manifestJson, Consumer<URI> onEndpointReady)
      throws InterruptedException;
}
