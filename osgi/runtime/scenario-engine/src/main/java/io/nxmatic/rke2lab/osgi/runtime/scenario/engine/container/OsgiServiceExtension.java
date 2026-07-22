package io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container;

import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.ByteBuddyStageClassCreator;
import com.tngtech.jgiven.impl.DefaultStageCreator;
import com.tngtech.jgiven.impl.StageCreator;
import com.tngtech.jgiven.impl.intercept.StepInterceptor;
import io.nxmatic.rke2lab.seed.broker.port.RunGate;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;
import org.osgi.framework.FrameworkUtil;

/**
 * The push bridge between jGiven and the OSGi registry: before the body runs, it INJECTS every
 * {@link OsgiService}-annotated {@code Optional<T>} field from the scenario bundle's registry
 * ({@link ScenarioRegistry}) — the DS {@code @Reference} a scenario/stage cannot have (both are
 * instantiated outside SCR). It replaces the byte-identical {@code
 * ScenarioRegistry.of(this).require} / {@code .optional} pull triad every scion carried by hand: a
 * scenario (or a stage) now DECLARES its collaborators as {@code Optional} fields and this fills
 * them.
 *
 * <p>Two injection sites, one {@link OsgiServiceInjector} (consulting the run-mode frontier once)
 * shared between them:
 *
 * <ul>
 *   <li>the SCENARIO test instance — filled here, as a {@link TestInstancePostProcessor}, in the
 *       same slot the inbound seeds ({@link ScenarioInputSeed}, {@code SessionSeed}) fill theirs;
 *   <li>every jGiven STAGE — filled as it is created, because a stage is instantiated by jGiven
 *       (not the JUnit engine), so a {@code TestInstancePostProcessor} never sees it. A custom
 *       {@link StageCreator} wrapping jGiven's default injects each stage the body builds, holding
 *       the SAME injector. Installing it in post-processing is safe: jGiven's per-test {@code
 *       startScenario} reuses the executor (it does not reset the stage creator), and stages are
 *       created lazily at {@code given()}/{@code when()}/{@code then()} — after this ran. This is
 *       what lets a scenario stop THREADING collaborators to its stages as step parameters: the
 *       stage declares them.
 * </ul>
 *
 * <p>Opt-in: a scenario whose class is not bundle-loaded is a no-op (the host-flat root — it
 * receives its world through {@code ConnectionReceiver}, and has no bundle registry to resolve
 * against). A bundle scenario with no annotated field on the instance nor on any stage still
 * installs the stage creator, but injection is then a per-stage no-op — harmless.
 *
 * <p>The mode FRONTIER lives in the {@link OsgiServiceInjector}: it reads the ambient {@link
 * RunGate} ONCE when built, so the run mode is consulted in one place and every scenario and stage
 * stays mode-blind.
 */
public final class OsgiServiceExtension
    implements TestInstancePostProcessor, AfterTestExecutionCallback {

  private static final Namespace NAMESPACE = Namespace.create(OsgiServiceExtension.class);
  private static final String REGISTRY = "scenario-registry";

  @Override
  public void postProcessTestInstance(Object testInstance, ExtensionContext context) {
    // Host-flat root: not bundle-loaded, so there is no bundle registry to resolve against — and it
    // receives its world through ConnectionReceiver, not @OsgiService. Nothing to inject.
    if (FrameworkUtil.getBundle(testInstance.getClass()) == null) {
      return;
    }
    // ONE registry per scenario, stashed for the boundary close: it HOLDS every service it resolves
    // (a delayed SCR component stays active, its @References bound) until afterTestExecution
    // releases
    // them — the deferral that stops a resolved component being torn down under the injected field
    // before the body calls it (the materializer's "0 providers" defect).
    final ScenarioRegistry registry = ScenarioRegistry.of(testInstance);
    context.getStore(NAMESPACE).put(REGISTRY, registry);
    final OsgiServiceInjector injector = OsgiServiceInjector.forRegistry(registry);

    // The scenario test instance (its seeds, plus any collaborator it still declares itself).
    injector.inject(testInstance);

    // Every stage the body creates: jGiven instantiates stages, so they are reached through a
    // custom
    // StageCreator holding the same injector. The scenario's collaborators live on the stages now.
    if (testInstance instanceof ScenarioTestBase<?, ?, ?> scenarioTest) {
      scenarioTest.getScenario().setStageCreator(new InjectingStageCreator(injector));
    }
  }

  /**
   * The scenario boundary: release the services the registry held, now the body that used the
   * injected collaborators has run. Fires per scenario ({@code @Test} method), so a nested scion —
   * played on its own launcher — brackets its own registry, and every delayed component stayed
   * active for exactly its scenario's duration.
   */
  @Override
  public void afterTestExecution(ExtensionContext context) {
    final ScenarioRegistry registry =
        context.getStore(NAMESPACE).get(REGISTRY, ScenarioRegistry.class);
    if (registry != null) {
      registry.close();
    }
  }

  /**
   * jGiven's default stage creator, wrapped to {@code @OsgiService}-inject each stage it builds. It
   * reconstructs the identical default jGiven would use ({@link DefaultStageCreator} over a {@link
   * ByteBuddyStageClassCreator}) and delegates to it, so stage bytecode is created exactly as
   * before — then hands the new stage to the shared {@link OsgiServiceInjector}.
   */
  private static final class InjectingStageCreator implements StageCreator {

    private final StageCreator delegate = new DefaultStageCreator(new ByteBuddyStageClassCreator());
    private final OsgiServiceInjector injector;

    InjectingStageCreator(OsgiServiceInjector injector) {
      this.injector = injector;
    }

    @Override
    public <T> T createStage(Class<T> stageClass, StepInterceptor interceptor) {
      final T stage = delegate.createStage(stageClass, interceptor);
      injector.inject(stage);
      return stage;
    }
  }
}
