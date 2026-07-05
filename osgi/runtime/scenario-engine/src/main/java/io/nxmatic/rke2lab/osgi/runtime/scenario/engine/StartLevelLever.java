package io.nxmatic.rke2lab.osgi.runtime.scenario.engine;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.startlevel.FrameworkStartLevel;

/**
 * The lever (spec Figure 4): moves the OSGi framework's SINGLE global start-level cursor and BLOCKS
 * until the move has settled. The cursor is single and global — the framework is AT level N, never
 * "at 4 here and 3 there"; what nests is the JUnit scope tree, which maps to ordered moves of this
 * one cursor in time.
 *
 * <p>The lever does NOT assign a bundle's start level — that is the {@code BootPlanner}'s
 * role→level authority ({@code BootPlan.START_LEVEL_*}, applied per bundle via {@code
 * BundleStartLevel} at boot). The lever only requests where the single cursor sits; the levels it
 * targets are the {@code BootPlan} constants, so there is one source of the scale.
 *
 * <p>Per OSGi R8 ({@code FrameworkStartLevel.setStartLevel}): lowering the active level stops the
 * bundles above it with {@code STOP_TRANSIENT} (arrests them without touching persistent
 * autostart); the call is ASYNCHRONOUS and fires {@link FrameworkEvent#STARTLEVEL_CHANGED} on
 * completion. So {@link #raiseTo}/{@link #descendTo} register a one-shot listener and await a latch
 * — the same latch-on-{@code FrameworkListener} shape the boot already uses to await {@code
 * STARTED}, one event later — making the move synchronous. Re-ascending re-lights the
 * transiently-stopped bundles from their unchanged autostart, no explicit start.
 */
public final class StartLevelLever {

  /**
   * How long a cursor move waits for STARTLEVEL_CHANGED before failing — mirrors the boot's wait.
   */
  private static final long SETTLE_TIMEOUT_SECONDS = 30;

  private final FrameworkStartLevel cursor;

  public StartLevelLever(BundleContext context) {
    this.cursor = context.getBundle(0).adapt(FrameworkStartLevel.class);
  }

  /** The level the single global cursor currently sits at. */
  public int current() {
    return cursor.getStartLevel();
  }

  /**
   * Raise the cursor to {@code level}, re-lighting bundles at or below it; blocks until settled.
   */
  public void raiseTo(int level) throws InterruptedException {
    moveTo(level);
  }

  /**
   * Descend the cursor to {@code level}, transiently stopping bundles above it (autostart intact);
   * blocks until STARTLEVEL_CHANGED so the world has settled when this returns.
   */
  public void descendTo(int level) throws InterruptedException {
    moveTo(level);
  }

  private void moveTo(int level) throws InterruptedException {
    final CountDownLatch settled = new CountDownLatch(1);
    cursor.setStartLevel(
        level,
        event -> {
          if (event.getType() == FrameworkEvent.STARTLEVEL_CHANGED) {
            settled.countDown();
          }
        });
    if (!settled.await(SETTLE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      throw new IllegalStateException(
          "start-level cursor did not settle at "
              + level
              + " within "
              + SETTLE_TIMEOUT_SECONDS
              + "s");
    }
  }
}
