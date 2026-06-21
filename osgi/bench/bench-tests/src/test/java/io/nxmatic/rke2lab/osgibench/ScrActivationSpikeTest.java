package io.nxmatic.rke2lab.osgibench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.nxmatic.rke2lab.junit.testkit.FelixFrameworkExtension;
import io.nxmatic.rke2lab.junit.testkit.OsgiSpike;
import io.nxmatic.rke2lab.osgibench.scr.Greeter;
import io.nxmatic.rke2lab.osgibench.scr.GreetingClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * R1 — the geste-B proof, the activation-time twin of P1's resolve/refuse. On a real embedded Felix
 * with felix.scr running, a trivial {@code @Component} PUBLISHES a service and a mandatory
 * {@code @Reference} BINDS it — the consumption half the bench (resolution + Metatype
 * introspection) never exercised. The anti-cheat (consumer stays unsatisfied when the provider is
 * absent) lives in its twin {@link ScrUnsatisfiedReferenceSpikeTest}, which needs a framework with
 * no provider bundle.
 *
 * <p>Access is TYPED: {@code system.packages.extra} exports the api package from the system bundle,
 * so the test and the bundles share one copy of {@link Greeter} / {@link GreetingClient} — the same
 * trick the Metatype proof uses for {@code MetaTypeService}.
 */
@OsgiSpike
class ScrActivationSpikeTest {

  // The api package comes from the system bundle (one exporter, matching version), so the provider,
  // the consumer and this test all share one copy of Greeter — no api bundle is installed.
  @RegisterExtension
  static final FelixFrameworkExtension felix =
      FelixFrameworkExtension.builder()
          .withScr()
          .systemPackages("io.nxmatic.rke2lab.osgibench.scr;version=0.1.0")
          .installBundles("scr-provider", "scr-consumer")
          .build();

  @Test
  void componentPublishesAndReferenceBinds() throws Exception {
    // PUBLISH: SCR activated the provider @Component → its Greeter is in the registry.
    Greeter greeter = felix.awaitService(Greeter.class, 5000);
    assertNotNull(greeter, "SCR published the provider's Greeter service");

    // BIND: the consumer's GreetingClient appears ONLY once its mandatory @Reference is bound, and
    // it calls through to the injected provider.
    GreetingClient client = felix.awaitService(GreetingClient.class, 5000);
    assertNotNull(client, "SCR activated the consumer once its @Reference was bound");
    assertEquals("hello, scr", client.greeting(), "the bound Greeter was injected and called");
  }
}
