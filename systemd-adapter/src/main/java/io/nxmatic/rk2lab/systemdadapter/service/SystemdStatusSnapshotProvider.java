package io.nxmatic.rk2lab.systemdadapter.service;

import io.nxmatic.rk2lab.systemdadapter.api.SystemdStatusSnapshot;

public interface SystemdStatusSnapshotProvider {

  SystemdStatusSnapshot currentSnapshot();
}
