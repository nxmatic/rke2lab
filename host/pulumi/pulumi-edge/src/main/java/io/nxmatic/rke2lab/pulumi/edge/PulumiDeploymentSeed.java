package io.nxmatic.rke2lab.pulumi.edge;

import com.pulumi.deployment.DeploymentInstance;
import com.pulumi.deployment.internal.DeploymentInstanceHolder;
import java.util.function.Consumer;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.engine.support.store.Namespace;
import org.junit.platform.engine.support.store.NamespacedHierarchicalStore;

/**
 * Carries the live Pulumi {@link DeploymentInstance} across the launcher membrane onto the WORKER
 * thread — the one thing {@code SessionSeed} cannot do by value, because the deployment is a
 * host-only handle held in a plain {@link ThreadLocal} (NOT inheritable), so the driver's thread
 * has it but the {@code JUnitLauncherCore} worker that plays the scenario does not. A scenario that
 * creates {@code com.pulumi} resources (the instance GROW) needs {@code Deployment.getInstance()}
 * to resolve on the worker; this seeds it there. It lives in {@code pulumi-edge} with its peers
 * ({@code RunMode}, {@code LiveGate}) — the host-side Pulumi-frontier concerns.
 *
 * <p>NOT the cellar: the deployment is a live, non-serialisable handle, host-only — it cannot cross
 * the seam. It rides the SAME in-JVM session channel as {@code SessionSeed} (the twin of {@code
 * RunRoleSeed}): the driver seeds it via {@link #into} on the launcher session store; this
 * extension, whose callback runs ON the worker, reads it back and installs it with {@link
 * DeploymentInstanceHolder#setInstance} before the body. Only the ROOT scenario needs it (it shares
 * the driver's session); a sown scion opens its own session and creates no Pulumi resources. The
 * worker thread dies after the run, so the installed thread-local dies with it — no teardown hook
 * is needed (the driver's own thread keeps its own deployment slot).
 */
public final class PulumiDeploymentSeed implements BeforeEachCallback {

  private static final String KEY = "pulumi-deployment";
  private static final String[] NS_PARTS = {PulumiDeploymentSeed.class.getName(), KEY};

  /** The launcher's seeding consumer — put the captured deployment into the session store. */
  public static Consumer<NamespacedHierarchicalStore<Namespace>> into(
      DeploymentInstance deployment) {
    final Namespace ns = Namespace.create((Object[]) NS_PARTS);
    return store -> store.put(ns, KEY, deployment);
  }

  /**
   * On the worker thread, before the body: install the seeded deployment into Pulumi's thread-local
   * so {@code new Project/Instance/...} resolve. No-op when none was seeded (a standalone run, or a
   * scion session) — the GROW beat itself skips when the deployment is absent.
   */
  @Override
  public void beforeEach(ExtensionContext context) {
    final ExtensionContext.Namespace ns = ExtensionContext.Namespace.create((Object[]) NS_PARTS);
    final DeploymentInstance seeded = context.getStore(ns).get(KEY, DeploymentInstance.class);
    if (seeded != null) {
      DeploymentInstanceHolder.setInstance(seeded);
    }
  }

  /** Whether a live Pulumi deployment is installed on THIS thread — the GROW beat's gate. */
  public static boolean isDeploymentPresent() {
    return DeploymentInstanceHolder.getInstanceOptional().isPresent();
  }
}
