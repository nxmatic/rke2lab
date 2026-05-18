#!/usr/bin/env -S bash -eu -o pipefail

: "Load RKE2 environment" # @codebase
source /srv/host/systemd-scripts.d/rke2lab-env-load.sh
rke2lab::env:load

log() {
	printf '[systemd-adapter-install] %s\n' "$*"
}

SYSTEMD_ADAPTER_SERVICE_ROOT="${RKE2LAB_SYSTEMD_ADAPTER_DIR}"
SYSTEMD_ADAPTER_CURRENT_DIR="${SYSTEMD_ADAPTER_SERVICE_ROOT}/current"
SYSTEMD_ADAPTER_LOG_DIR="${SYSTEMD_ADAPTER_SERVICE_ROOT}/log"
SYSTEMD_ADAPTER_JAR_NAME="rke2lab-systemd-adapter-exec.jar"
SYSTEMD_ADAPTER_JAR_TARGET="${SYSTEMD_ADAPTER_CURRENT_DIR}/${SYSTEMD_ADAPTER_JAR_NAME}"
SYSTEMD_ADAPTER_LIBEXEC_DIR="${RKE2LAB_SYSTEMD_LIBEXEC_DIR}/rke2lab-systemd-adapter"
SOURCE_JAR="${SYSTEMD_ADAPTER_LIBEXEC_DIR}/${SYSTEMD_ADAPTER_JAR_NAME}"

if [[ ! -f "${SOURCE_JAR}" ]]; then
	log "missing adapter executable jar: ${SOURCE_JAR}"
	exit 1
fi

if ! source <(flox activate --dir /var/lib/rancher/rke2) >/dev/null 2>&1; then
	log "failed to activate flox env: /var/lib/rancher/rke2"
	exit 1
fi

if ! command -v java >/dev/null 2>&1; then
	log "java runtime is required but not found in flox env: /var/lib/rancher/rke2"
	exit 1
fi

mkdir -p "${SYSTEMD_ADAPTER_CURRENT_DIR}" "${SYSTEMD_ADAPTER_LOG_DIR}"
install -m 0644 "${SOURCE_JAR}" "${SYSTEMD_ADAPTER_JAR_TARGET}"

log "materialized adapter runtime into ${SYSTEMD_ADAPTER_SERVICE_ROOT}"
log "source jar: ${SOURCE_JAR}"
log "target jar: ${SYSTEMD_ADAPTER_JAR_TARGET}"
