#!/usr/bin/env -S bash -exu -o pipefail

source <(flox activate --dir /var/lib/rancher/rke2)

: "Install the RKE2 server or agent binaries"
curl -sfL https://get.rke2.io | env DEBUG=1 sh -

: "Enable shared mount service"
systemctl daemon-reload
systemctl enable rke2lab-remount-shared
