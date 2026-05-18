package io.nxmatic.rk2lab.systemdadapter.service;

import io.nxmatic.rk2lab.systemdcontract.api.SystemdStatusSnapshot;

public interface SystemdStatusSnapshotProvider {

  SystemdStatusSnapshot currentSnapshot();
}
