package io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container;

import io.seedmatic.rke2lab.seed.broker.port.RunGate;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Optional;

/**
 * Fills the {@link OsgiService}-annotated {@code Optional<T>} fields of ANY holder — a scenario
 * test instance or a jGiven stage — from a fixed {@link ScenarioRegistry} through a fixed frontier
 * {@link GardeningSelection}. An instance, not a static helper: the frontier is consulted ONCE (the
 * run mode read from the ambient {@link RunGate} when the selection is built), and the resulting
 * injector is PASSED to the collaborators that need it — {@link OsgiServiceExtension} uses it on
 * the test instance and hands the SAME instance to the stage creator, which uses it on every stage.
 * So both injection sites resolve identically, against the same registry and the same mode
 * decision.
 *
 * <p>Each field is an {@code Optional<T>}: the injector OWNS presence, so the holder never touches
 * a null. {@link OsgiService#await()} chooses the resolution — an awaited {@link
 * ScenarioRegistry#require} (wrapped present) for a required collaborator racing the SCR extender,
 * or a {@link ScenarioRegistry#optional} snapshot for an optional one.
 */
final class OsgiServiceInjector {

  private final ScenarioRegistry registry;
  private final GardeningSelection selection;

  /**
   * Build an injector bound to {@code registry}, reading the ambient {@link RunGate} ONCE to fix
   * the {@link GardeningSelection} — the single place the run mode is consulted, so every holder
   * this injector fills stays mode-blind.
   */
  static OsgiServiceInjector forRegistry(ScenarioRegistry registry) {
    return new OsgiServiceInjector(
        registry, GardeningSelection.from(registry.optional(RunGate.class)));
  }

  private OsgiServiceInjector(ScenarioRegistry registry, GardeningSelection selection) {
    this.registry = registry;
    this.selection = selection;
  }

  /**
   * Fill every {@code @OsgiService Optional<T>} field declared on {@code holder} OR any of its
   * superclasses. The hierarchy walk matters for a jGiven stage: jGiven instantiates a ByteBuddy
   * SUBCLASS of the stage, so the {@code @OsgiService} fields (declared on the real stage class)
   * sit on a superclass, invisible to a single {@code getDeclaredFields()} on the runtime type.
   */
  void inject(Object holder) {
    for (Class<?> type = holder.getClass(); type != null && type != Object.class; ) {
      for (Field field : type.getDeclaredFields()) {
        final OsgiService annotation = field.getAnnotation(OsgiService.class);
        if (annotation == null) {
          continue;
        }
        if (field.getType() != Optional.class) {
          throw new IllegalStateException(
              "@OsgiService field '"
                  + field.getName()
                  + "' must be an Optional<ServiceType> (the injector owns presence), not "
                  + field.getType().getSimpleName());
        }
        field.setAccessible(true);
        try {
          field.set(holder, resolve(field, annotation.await()));
        } catch (IllegalAccessException ex) {
          throw new IllegalStateException(
              "cannot inject @OsgiService field '" + field.getName() + "'", ex);
        }
      }
      type = type.getSuperclass();
    }
  }

  /**
   * Resolve one {@code Optional<T>} field through the frontier filter: an {@code await} field gets
   * the required service (awaited from SCR) wrapped in a present {@code Optional}; a non-await
   * field gets the snapshot {@code Optional}. The absence message names the field so a wiring bug
   * points straight at the unsatisfied declaration.
   */
  private Optional<?> resolve(Field field, boolean await) {
    final Class<?> type = optionalElementType(field);
    final String filter = selection.filter();
    if (await) {
      return Optional.of(
          registry.require(
              type,
              filter,
              "no "
                  + type.getSimpleName()
                  + " in the registry for @OsgiService field '"
                  + field.getName()
                  + "' (the live edge or a test mock must publish one)"));
    }
    return registry.optional(type, filter);
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
