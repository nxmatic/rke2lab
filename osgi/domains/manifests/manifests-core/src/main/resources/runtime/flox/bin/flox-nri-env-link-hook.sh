#!/usr/bin/env -S bash -euxo pipefail
# OCI CreateContainer hook: materialize a flox env as a fine-grained symlink farm
# in the container rootfs.
#
# Why a symlink farm rather than the older overlay-mount of .flox:
# A flox env's static half (env/manifest.toml + env/manifest.lock) is immutable
# and lives in /nix/store, which the container already sees (the nix-store
# overlay lowers from the host store). Only flox's run/, cache/, and log/ are
# mutable. So we build a REAL local .flox directory whose env/ is a symlink into
# the store and whose run/cache/log are real writable dirs.
#
# The split MUST be at .flox-internal granularity. Symlinking the whole .flox
# into the store makes flox resolve run/ relative to the symlink's real target
# and try to create a GC root inside /nix/store, which nix refuses
# ("creating a garbage collector root ... in the Nix store is forbidden").
# See docs/flox-store-resolved-runtime-and-builder.adoc.
#
# Runs in the container's mount namespace before pivot_root (CreateContainer),
# so paths written here persist into the running container.
#
# Reads the OCI container state JSON on stdin to extract the bundle path; the
# container rootfs is "${bundle}/rootfs".
#
# Usage: flox-nri-env-link-hook.sh <env-store-path> <target-flox-dir>
#   <env-store-path>  absolute /nix/store path of the env subtree exposing env/
#                     (i.e. <store-path>/env/{manifest.toml,manifest.lock}).
#   <target-flox-dir> absolute path inside the container (e.g. /root/.flox) where
#                     the .flox symlink farm is materialized.
#
# Logging: stdout/stderr are piped to systemd-journald via logger(1). View with:
#   journalctl -ft flox-nri-env-link-hook

TAG=flox-nri-env-link-hook
exec > >(logger --id=$$ -t "$TAG" -p daemon.info)
exec 2> >(logger --id=$$ -t "$TAG" -p daemon.err)

env_store_path="$1"
target_flox_rel="$2"

state="$(cat)"
bundle="$(printf '%s' "$state" | sed -n 's/.*"bundle"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n1)"
container_id="$(printf '%s' "$state" | sed -n 's/.*"id"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n1)"

echo "hook invoked container=${container_id} bundle=${bundle} env=${env_store_path} target=${target_flox_rel}"

if [ -z "$bundle" ]; then
	echo "ERROR: could not parse bundle path from OCI state" >&2
	exit 1
fi

rootfs="${bundle%/}/rootfs"
if [ ! -d "$rootfs" ]; then
	echo "ERROR: rootfs does not exist: ${rootfs}" >&2
	exit 1
fi

# The store path is addressed identically inside and outside the container: the
# container's /nix/store overlay lowers from the host store, so the same
# /nix/store/<hash>-<env> resolves on both sides. We record it verbatim as the
# env/ symlink target — valid after pivot_root.
if [ ! -d "${env_store_path}/env" ]; then
	echo "ERROR: env store path missing env/ subdir: ${env_store_path}/env" >&2
	exit 1
fi

target_flox="${rootfs}${target_flox_rel}"

# Real local .flox directory: env/ symlinks into the store (immutable), env.json
# is a minimal local marker, and run/cache/log are real writable dirs so
# `flox activate` writes its volatile state locally and leaves the store pristine.
mkdir -p "${target_flox}" "${target_flox}/run" "${target_flox}/cache" "${target_flox}/log"
ln -sfn "${env_store_path}/env" "${target_flox}/env"

if [ ! -e "${target_flox}/env.json" ]; then
	printf '%s' '{"name": "default", "version": 1}' >"${target_flox}/env.json"
fi

echo "materialized flox env farm at ${target_flox_rel} (env -> ${env_store_path}/env)"

exit 0
