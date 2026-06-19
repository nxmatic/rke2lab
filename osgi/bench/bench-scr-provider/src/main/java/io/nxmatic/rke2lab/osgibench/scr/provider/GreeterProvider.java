package io.nxmatic.rke2lab.osgibench.scr.provider;

import io.nxmatic.rke2lab.osgibench.scr.Greeter;
import org.osgi.service.component.annotations.Component;

/**
 * The PUBLISH half of the geste-B proof: a trivial DS component, alone in its bundle so the
 * anti-cheat can omit it. bnd generates {@code OSGI-INF/…GreeterProvider.xml} + the {@code
 * Service-Component} header from this {@code @Component}; at bundle start SCR activates it and
 * registers a {@link Greeter} in the service registry — no hand-written descriptor, no manual
 * {@code registerService}.
 */
@Component(service = Greeter.class)
public final class GreeterProvider implements Greeter {
  @Override
  public String greet(String name) {
    return "hello, " + name;
  }
}
