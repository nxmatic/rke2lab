package io.seedmatic.rke2lab.bbox.contract;

import io.seedmatic.rke2lab.seed.broker.port.Amendment;
import io.seedmatic.rke2lab.seed.broker.port.SeedContract;
import java.util.Optional;

/**
 * The wire contract for the bbox {@code runbook} trigger — the activation payload the
 * reconciliation scenario is played with. The INPUT twin of {@link BboxHarvest} (the reaped
 * output): where the harvest carries the reconciled reservations back, this carries the ONE thing
 * the scion cannot derive from its static blueprint — the router contact ({@code uri} + {@code
 * password}), which only the host holds (it reads {@code .secrets:lan.bbox.*}).
 *
 * <p>Its single component bears the {@link Amendment#FACET} role: the whole {@link Router} contact
 * is ONE composite the host fills at the amend door, naming no bbox field — {@link Router} mirrors
 * the {@code {uri, password}} shape the host contributes, and the assembler binds it onto {@link
 * #router}. When no contributor offers FACET (a bare {@code shape} probe, or a survey run where the
 * mock edge ignores the contact), it falls to {@link #defaults()}.
 */
@SeedContract("runbook")
public record BboxRunbookInput(@Amendment(Amendment.FACET) Router router) {

  /**
   * The unamended seed the scion holds before a sow arrives — the public router URL and an ABSENT
   * password ({@link Optional#empty()}, never a blank string). A live run fills the password via
   * the host FACET amendment; a survey (the surveying/mock reconciler contacts the router zero
   * times) plays green on the empty one. Never a partial instance: {@link Router} is complete.
   */
  public static BboxRunbookInput defaults() {
    return new BboxRunbookInput(Router.defaults());
  }

  /**
   * The router contact — the single {@link Amendment#FACET} composite, mirroring the {@code {uri,
   * password}} JSON the host contributes at the door. The scion feeds these to {@link
   * BboxReconciler#reconcile}. The {@code password} is an {@link Optional}: EMPTY = unamended (a
   * survey / the mock edge, which ignores it); present = the live secret the host read from {@code
   * .secrets}. Absence is an empty {@link Optional}, never a blank string.
   *
   * <p>HTTPS is mandatory on the {@code uri}: the Bbox 302-redirects http→https, and a POST
   * following that redirect is downgraded to GET, which the POST-only {@code /api/v1/login} answers
   * 404 (the cause of the reconcile's "404 Operation not found"). The cert is a public DigiCert for
   * mabbox.bytel.fr, so no trust override is needed.
   */
  public record Router(String uri, Optional<String> password) {

    public static Router defaults() {
      return new Router("https://mabbox.bytel.fr", Optional.empty());
    }
  }
}
