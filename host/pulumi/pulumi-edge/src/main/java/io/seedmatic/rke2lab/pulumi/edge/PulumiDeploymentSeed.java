package io.seedmatic.rke2lab.pulumi.edge;

import com.pulumi.deployment.DeploymentInstance;
import com.pulumi.deployment.internal.DeploymentInstanceHolder;
import io.seedmatic.rke2lab.seed.broker.port.Parcel;
import java.util.Optional;
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

  /**
   * The {@link Parcel} the live deployment on THIS thread targets — the RUN's own stack identity.
   * Read ONCE host-side (in the GIVEN, where the deployment is installed) and captured by {@code
   * PulumiCellar}, so a READER can tell the run stack from a side stack by IDENTITY, WITHOUT the
   * thread-local — a scion plays off the deployment thread, yet must still read the run stack's
   * current state. Empty when no deployment is installed (a standalone/preview run). Confines the
   * {@code com.pulumi.deployment.internal} coupling here, the one class that owns the handle.
   */
  public static Optional<Parcel> runStack() {
    return DeploymentInstanceHolder.getInstanceOptional()
        .map(d -> new Parcel(d.getProjectName(), d.getStackName()));
  }

  /**
   * Whether the live deployment on THIS thread targets {@code project/stack} — the discriminant a
   * WRITE uses (staging into the one live deployment needs it on this very thread; a side stack
   * like the doctor's ledger files eagerly via its own out-of-run {@code up}). A READ tells the run
   * stack by identity instead (see {@link #runStack}). False when no deployment is installed.
   */
  public static boolean targets(String project, String stack) {
    return runStack()
        .map(p -> project.equals(p.project()) && stack.equals(p.stack()))
        .orElse(false);
  }
}
