package io.nxmatic.rk2lab.systemdadapter.api;

import io.nxmatic.rk2lab.systemdadapter.service.SystemdStatusSnapshotProvider;
import io.nxmatic.rk2lab.systemdcontract.api.SystemdAdapterApiPaths;
import io.nxmatic.rk2lab.systemdcontract.api.SystemdStatusSnapshot;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping
public class SystemdStatusController {

  private final SystemdStatusSnapshotProvider snapshotProvider;

  public SystemdStatusController(SystemdStatusSnapshotProvider snapshotProvider) {
    this.snapshotProvider = snapshotProvider;
  }

  @GetMapping(SystemdAdapterApiPaths.STATUS_SYSTEMD)
  public SystemdStatusSnapshot status() {
    return snapshotProvider.currentSnapshot();
  }

  @GetMapping(SystemdAdapterApiPaths.HEALTHZ_SYSTEMD)
  public ResponseEntity<Map<String, Object>> healthz() {
    final SystemdStatusSnapshot snapshot = snapshotProvider.currentSnapshot();
    final Map<String, Object> body =
        Map.of(
            "healthy",
            snapshot.runtimePrecheckReady(),
            "mandatoryTarget",
            snapshot.mandatoryTarget(),
            "mandatoryTargetState",
            snapshot.mandatoryTargetState(),
            "pendingJobs",
            snapshot.pendingJobs(),
            "failedUnits",
            snapshot.failedUnits(),
            "connectionContext",
            snapshot.connectionContext(),
            "summary",
            snapshot.summary());

    if (snapshot.runtimePrecheckReady()) {
      return ResponseEntity.ok(body);
    }
    return ResponseEntity.status(503).body(body);
  }
}
