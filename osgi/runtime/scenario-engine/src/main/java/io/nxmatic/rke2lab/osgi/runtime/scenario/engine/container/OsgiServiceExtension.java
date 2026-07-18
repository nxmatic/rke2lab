package io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Optional;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;

/**
 * The push bridge between jGiven and the OSGi registry: before the body runs, it INJECTS every
 * {@link OsgiService}-annotated field of the scenario from its bundle's registry ({@link
 * ScenarioRegistry}) — the DS {@code @Reference} a scenario cannot have (it is instantiated by the
 * JUnit engine, not by SCR). It replaces the byte-identical {@code
 * ScenarioRegistry.of(this).require(...)} / {@code .optional(...)} pull triad every scion carried
 * by hand: the scenario now DECLARES its collaborators as {@code Optional} fields and this fills
 * them.
 *
 * <p>Every {@link OsgiService} field is an {@code Optional<T>} — so the bridge OWNS the presence
 * concern (the scenario never touches a null): {@link OsgiService#await()} decides how it resolves,
 * an awaited {@link ScenarioRegistry#require} (wrapped in a present {@code Optional}) for a
 * required collaborator racing the SCR extender, or a non-awaited {@link ScenarioRegistry#optional}
 * snapshot for an optional one. The body unwraps a required field with {@code orElseThrow} and an
 * optional one with {@code map}/{@code ifPresent}. Runs as a {@link TestInstancePostProcessor},
 * before jGiven's own post-processing, so the fields are set before the GIVEN — the same slot the
 * inbound seeds ({@link ScenarioInputSeed}, {@code SessionSeed}) fill their fields in.
 *
 * <p>Opt-in: a scenario with no annotated field is a no-op (the host-flat root declares none — it
 * receives its world through {@code ConnectionReceiver}, and has no bundle registry to resolve
 * against anyway).
 */
public final class OsgiServiceExtension implements TestInstancePostProcessor {

  @Override
  public void postProcessTestInstance(Object testInstance, ExtensionContext context)
      throws Exception {
    ScenarioRegistry registry = null;
    for (Field field : testInstance.getClass().getDeclaredFields()) {
      final OsgiService annotation = field.getAnnotation(OsgiService.class);
      if (annotation == null) {
        continue;
      }
      if (field.getType() != Optional.class) {
        throw new IllegalStateException(
            "@OsgiService field '"
                + field.getName()
                + "' must be an Optional<ServiceType> (the bridge owns presence), not "
                + field.getType().getSimpleName());
      }
      if (registry == null) {
        registry = ScenarioRegistry.of(testInstance);
      }
      field.setAccessible(true);
      field.set(testInstance, resolve(registry, field, annotation.await()));
    }
  }

  /**
   * Resolve one {@code Optional<T>} field: an {@code await} field gets the required service
   * (awaited from SCR) wrapped in a present {@code Optional}; a non-await field gets the snapshot
   * {@code Optional}. The absence message names the field so a wiring bug points straight at the
   * unsatisfied declaration.
   */
  private static Optional<?> resolve(ScenarioRegistry registry, Field field, boolean await) {
    final Class<?> type = optionalElementType(field);
    if (await) {
      return Optional.of(
          registry.require(
              type,
              "no "
                  + type.getSimpleName()
                  + " in the registry for @OsgiService field '"
                  + field.getName()
                  + "' (the live edge or a test mock must publish one)"));
    }
    return registry.optional(type);
  }

  /** The {@code T} of an {@code Optional<T>} field — the service type to resolve. */
  private static Class<?> optionalElementType(Field field) {
    final Type generic = field.getGenericType();
    if (generic instanceof ParameterizedType parameterized) {
      final Type[] args = parameterized.getActualTypeArguments();
      if (args.length == 1 && args[0] instanceof Class<?> element) {
        return element;
      }
    }
    throw new IllegalStateException(
        "@OsgiService field '"
            + field.getName()
            + "' must be a parameterised Optional<ServiceType>, not "
            + generic);
  }
}
