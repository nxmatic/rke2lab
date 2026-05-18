package io.nxmatic.rk2lab.systemdadapter.api;

import io.nxmatic.rk2lab.systemdadapter.service.SystemdStatusSnapshotProvider;
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

  @GetMapping("/status/systemd")
  public SystemdStatusSnapshot status() {
    return snapshotProvider.currentSnapshot();
  }

  @GetMapping("/healthz/systemd")
  public ResponseEntity<Map<String, Object>> healthz() {
    final SystemdStatusSnapshot snapshot = snapshotProvider.currentSnapshot();
    final Map<String, Object> body =
        Map.of(
            "healthy", snapshot.runtimePrecheckReady(),
            "mandatoryTarget", snapshot.mandatoryTarget(),
            "mandatoryTargetState", snapshot.mandatoryTargetState(),
            "pendingJobs", snapshot.pendingJobs(),
            "failedUnits", snapshot.failedUnits(),
            "summary", snapshot.summary());

    if (snapshot.runtimePrecheckReady()) {
      return ResponseEntity.ok(body);
    }
    return ResponseEntity.status(503).body(body);
  }
}
