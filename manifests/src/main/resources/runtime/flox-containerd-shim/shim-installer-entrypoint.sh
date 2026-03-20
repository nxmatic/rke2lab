#!/bin/sh
set -eux

: "Install bash and coreutils (GNU env) for script compatibility"
apk add --no-cache bash coreutils

: "Run the shim installer script"
/.sh/shim-installer.sh
