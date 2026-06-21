package io.nxmatic.rke2lab.osgibench;

import static org.junit.jupiter.api.Assertions.assertNull;

import io.nxmatic.rke2lab.junit.testkit.FelixFrameworkExtension;
import io.nxmatic.rke2lab.junit.testkit.OsgiSpike;
import io.nxmatic.rke2lab.osgibench.scr.Greeter;
import io.nxmatic.rke2lab.osgibench.scr.GreetingClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * R1 anti-cheat — the activation-time twin of P1's "stays unresolved when the host is absent". With
 * felix.scr running and the consumer bundle started but the PROVIDER bundle absent, SCR leaves the
 * consumer's mandatory {@code 1..1} {@code @Reference} UNSATISFIED, so its {@link GreetingClient}
 * service never reaches the registry. Proves the binding is real, not a coincidence: no provider,
 * no published consumer. Its own framework (separate class) so the provider bundle is genuinely
 * never installed.
 */
@OsgiSpike
class ScrUnsatisfiedReferenceSpikeTest {

  // The consumer is installed but NOT the provider — the anti-cheat is right here in the topology.
  // api from the system bundle: the consumer RESOLVES (the api is present) but SCR leaves it
  // unsatisfied because no Greeter is published — resolution succeeds, activation does not.
  @RegisterExtension
  static final FelixFrameworkExtension felix =
      FelixFrameworkExtension.builder()
          .withScr()
          .systemPackages("io.nxmatic.rke2lab.osgibench.scr;version=0.1.0")
          .installBundles("scr-consumer")
          .build();

  @Test
  void consumerStaysUnsatisfiedWhenProviderAbsent() throws Exception {
    assertNull(
        felix.awaitService(Greeter.class, 500), "no provider bundle — no Greeter is published");
    assertNull(
        felix.awaitService(GreetingClient.class, 500),
        "consumer's mandatory @Reference is unsatisfied — SCR never activates it, loudly");
  }
}
