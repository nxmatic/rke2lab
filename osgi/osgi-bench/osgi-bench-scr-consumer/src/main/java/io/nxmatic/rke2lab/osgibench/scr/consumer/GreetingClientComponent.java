package io.nxmatic.rke2lab.osgibench.scr.consumer;

import io.nxmatic.rke2lab.osgibench.scr.Greeter;
import io.nxmatic.rke2lab.osgibench.scr.GreetingClient;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * The BIND half of the geste-B proof, and the anti-cheat. A DS component whose mandatory {@code
 * 1..1} {@link Reference} to {@link Greeter} is the default cardinality: SCR leaves it UNSATISFIED
 * — so {@link GreetingClient} never reaches the registry — until a {@link Greeter} is published. In
 * its own bundle, so the test can start it with the provider bundle absent (stays unsatisfied) or
 * present (activates, registers, calls through). The activation-time twin of P1's resolve/refuse.
 */
@Component(service = GreetingClient.class)
public final class GreetingClientComponent implements GreetingClient {

  @Reference private Greeter greeter;

  @Override
  public String greeting() {
    return greeter.greet("scr");
  }
}
