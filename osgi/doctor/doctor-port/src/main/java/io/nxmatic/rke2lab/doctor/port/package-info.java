@org.osgi.annotation.versioning.Version("1.0.0")
// REALM_BOUNDARY WARN: this seam imports doctor.records (the boundary leak #1) — listed while the
// surface migrates to Documents; drop to return to the ERROR default once the import is gone.
@GovernedBy(value = StagingGate.REALM_BOUNDARY, level = EnforcementLevel.WARN)
package io.nxmatic.rke2lab.doctor.port;

import io.nxmatic.rke2lab.domain.annotations.EnforcementLevel;
import io.nxmatic.rke2lab.domain.annotations.GovernedBy;
import io.nxmatic.rke2lab.domain.annotations.StagingGate;
