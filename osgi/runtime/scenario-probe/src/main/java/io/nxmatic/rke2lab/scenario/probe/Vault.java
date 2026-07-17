package io.nxmatic.rke2lab.scenario.probe;

/**
 * The guard's host POJO, standing in for a real host bundle's internal type. A trivial domain
 * object with a PACKAGE-PRIVATE {@link #balance}: a consumer in another package (a black-box test
 * in another bundle) cannot read it — only same-package code can. The {@code -test} fragment,
 * attached to this bundle and authored in this package, reads it white-box, demonstrating the
 * internal-edge access the fragment-test model gives a real host like doctor-core.
 */
public final class Vault {

  // Package-private on purpose: the white-box surface the same-package fragment reaches.
  int balance;

  public void deposit(int amount) {
    this.balance += amount;
  }
}
