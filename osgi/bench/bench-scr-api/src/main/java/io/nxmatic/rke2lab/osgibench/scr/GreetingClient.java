package io.nxmatic.rke2lab.osgibench.scr;

/**
 * The service the consumer component publishes once SCR has bound its mandatory {@link Greeter}
 * reference. Its very presence in the registry is the BIND proof: SCR activates the consumer — and
 * therefore registers this service — only after the {@code 1..1} {@code @Reference} is satisfied.
 */
public interface GreetingClient {
  String greeting();
}
