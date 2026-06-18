#!/usr/bin/env -S bash -exuo pipefail
mount --make-shared /
mount --make-shared -t bpf bpf /sys/fs/bpf
mount --make-shared /run
