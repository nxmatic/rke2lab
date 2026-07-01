@org.osgi.annotation.versioning.Version("1.0.0")
// WARN level: the six Document coordinates migrate to their wire-record one at a time; each build
// lists the coordinates still lacking a @DocumentContract record (green backlog). Drop this
// annotation to return to the ERROR default once every coordinate has its wire-record — the
// SCHEMA_CONCORD lock (T10).
@GovernedBy(value = StagingGate.SCHEMA_CONCORD, level = EnforcementLevel.WARN)
package io.nxmatic.rke2lab.world.gateway.port;

import io.nxmatic.rke2lab.domain.annotations.EnforcementLevel;
import io.nxmatic.rke2lab.domain.annotations.GovernedBy;
import io.nxmatic.rke2lab.domain.annotations.StagingGate;
