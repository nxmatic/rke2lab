/**
 * The flat host control-plane. Governed at {@code REALM_BOUNDARY = WARN} while the host↔OSGi
 * surface is migrated to Documents (see docs/architecture/osgi/world-exchange-spec.adoc): the gate
 * lists every flat-realm class still referencing a bundle-only doctor.records type as a shrinking
 * worklist, build green. Drop this pose to return to the locked ERROR default once the migration
 * (Plan 2) is complete.
 */
@GovernedBy(value = StagingGate.REALM_BOUNDARY, level = EnforcementLevel.WARN)
package io.nxmatic.rke2lab.controlplane;

import io.nxmatic.rke2lab.domain.annotations.EnforcementLevel;
import io.nxmatic.rke2lab.domain.annotations.GovernedBy;
import io.nxmatic.rke2lab.domain.annotations.StagingGate;
