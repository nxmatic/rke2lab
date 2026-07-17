package io.nxmatic.rke2lab.scenario.probe;

import com.tngtech.jgiven.Stage;

/**
 * The guard's jGiven Stage, authored in the HOST's package so it touches {@link Vault#balance}
 * (package-private) WHITE-BOX — the access a black-box test in another bundle could not have. This
 * is the single Stage type jGiven subclasses via byte-buddy at runtime; because it is loaded by the
 * host bundle's classloader (the fragment shares it), jGiven's INJECTION strategy defines the proxy
 * into that same loader — which resolves {@code net.bytebuddy.*} only because the fragment
 * contributed that import to the host.
 */
public class VaultStage extends Stage<VaultStage> {

  private final Vault vault = new Vault();

  public VaultStage a_vault() {
    return self();
  }

  public VaultStage $_is_deposited(int amount) {
    vault.deposit(amount);
    return self();
  }

  /** Reads the package-private field white-box — the point of same-package authoring. */
  public VaultStage the_balance_is(int expected) {
    if (vault.balance != expected) {
      throw new AssertionError("expected balance " + expected + " but was " + vault.balance);
    }
    return self();
  }
}
