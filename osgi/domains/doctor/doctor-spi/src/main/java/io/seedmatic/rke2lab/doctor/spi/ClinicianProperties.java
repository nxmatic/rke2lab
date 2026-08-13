package io.seedmatic.rke2lab.doctor.spi;

/**
 * The single source of truth for the DS service properties a {@link Clinician} {@code @Component}
 * publishes and a consumer's {@code @Reference} target filter selects on — so the provider side and
 * the filter side can never drift (the identifier-catalog discipline; a hand-typed {@code
 * "clinician.role"} on one side and a typo on the other would silently bind nothing).
 *
 * <p>Two axes, both rooted at the GENUS {@code clinician.*}, never at one species:
 *
 * <ul>
 *   <li>{@link #ROLE} — the species: a {@link #ROLE_DIAGNOSTICIAN diagnostician} (a {@link
 *       Specialist}, reads a situation and reasons) or a {@link #ROLE_REMEDIATOR remediator} (a
 *       {@link Remediator}, administers a prescription). A remediator is a clinician but is NOT a
 *       doctor, so the discriminator lives on {@code clinician.*}, not {@code doctor.*}.
 *   <li>{@link #TIER} — the recursion level: a {@link #TIER_DOMAIN domain}-level clinician (the one
 *       a domain contributes to the doctor) vs a {@link #TIER_SUB sub}-level one a composite
 *       clinician collects internally. The tier filter is what stops a composite (a specialist that
 *       is itself a coordinator) from collapsing the whole hierarchy into one flat roster.
 * </ul>
 *
 * <p>The {@code PROP_*} entries are ready-made {@code key=value} strings for a {@code @Component}'s
 * {@code property} array; the {@code TARGET_*} entries are ready-made LDAP filters for a
 * {@code @Reference}'s {@code target}. All are compile-time constants (concatenation of constants),
 * so they are usable directly in those annotations.
 */
public final class ClinicianProperties {

  /** The species axis: which kind of clinician this is. */
  public static final String ROLE = "clinician.role";

  /**
   * A diagnostician — a {@link Specialist} that reads a situation and reasons toward a treatment.
   */
  public static final String ROLE_DIAGNOSTICIAN = "diagnostician";

  /** A remediator — a {@link Remediator} that administers a prescribed treatment. */
  public static final String ROLE_REMEDIATOR = "remediator";

  /** The recursion axis: the level at which this clinician participates. */
  public static final String TIER = "clinician.tier";

  /** Domain level — the clinician a domain contributes to the doctor's roster. */
  public static final String TIER_DOMAIN = "domain";

  /** Sub level — a member a composite clinician collects internally, hidden from the top roster. */
  public static final String TIER_SUB = "sub";

  /** {@code @Component property} entry: a domain-level diagnostician. */
  public static final String PROP_DIAGNOSTICIAN = ROLE + "=" + ROLE_DIAGNOSTICIAN;

  /** {@code @Component property} entry: a remediator. */
  public static final String PROP_REMEDIATOR = ROLE + "=" + ROLE_REMEDIATOR;

  /** {@code @Component property} entry: domain tier. */
  public static final String PROP_TIER_DOMAIN = TIER + "=" + TIER_DOMAIN;

  /** {@code @Component property} entry: sub tier. */
  public static final String PROP_TIER_SUB = TIER + "=" + TIER_SUB;

  /**
   * {@code @Reference target}: the domain-level diagnosticians the doctor's roster collects — the
   * top specialists only, never a composite's internal sub-specialists (which carry {@link
   * #TIER_SUB}).
   */
  public static final String TARGET_DOMAIN_DIAGNOSTICIANS =
      "(&(" + ROLE + "=" + ROLE_DIAGNOSTICIAN + ")(" + TIER + "=" + TIER_DOMAIN + "))";

  /** {@code @Reference target}: every remediator (the prescription-administering tier). */
  public static final String TARGET_REMEDIATORS = "(" + ROLE + "=" + ROLE_REMEDIATOR + ")";

  private ClinicianProperties() {}
}
