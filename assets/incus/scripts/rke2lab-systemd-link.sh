#!/usr/bin/env -S bash -exu -o pipefail

: "Load RKE2 environment" # @codebase
source /srv/host/scripts.d/rke2lab-env-load.sh
rke2lab::env:load

log() {
	printf '[systemd-link] %s\n' "$*"
}

# Wait for the bind-mount to appear and contain files (up to 30s)
for i in {1..30}; do
	if [[ -d "${RKE2LAB_SYSTEMD_DIR}" && $(find "${RKE2LAB_SYSTEMD_DIR}" -type f | wc -l) -gt 0 ]]; then
		break
	fi
	log "waiting for ${RKE2LAB_SYSTEMD_DIR} to be populated (attempt ${i}/30)"
	sleep 1
done

if [[ ! -d "${RKE2LAB_SYSTEMD_DIR}" ]]; then
	log "systemd directory not found: ${RKE2LAB_SYSTEMD_DIR}"
	exit 1
fi

mkdir -p /etc/systemd/system

: "Copy service unit files"
find "${RKE2LAB_SYSTEMD_DIR}" -maxdepth 1 -type f \( -name '*.service' -o -name '*.target' -o -name '*.mount' \) -exec cp {} /etc/systemd/system/ \;
log "copied systemd units from ${RKE2LAB_SYSTEMD_DIR}"

: "Copy service unit drop-in directories"
find "${RKE2LAB_SYSTEMD_DIR}" -maxdepth 1 -type d -name '*.d' -exec cp -r {} /etc/systemd/system/ \;
log "copied systemd override directories from ${RKE2LAB_SYSTEMD_DIR}"

: "Reload systemd to recognize new units"
systemctl daemon-reload
log "daemon-reload complete"

: "Enable all rke2lab units (services, targets, mounts)"
for unit_file in /etc/systemd/system/rke2lab-*.service /etc/systemd/system/rke2lab-*.target /etc/systemd/system/*.mount; do
	if [[ -f "${unit_file}" ]]; then
		unit_name=$(basename "${unit_file}")
		systemctl enable "${unit_name}" || log "warning: failed to enable ${unit_name}"
	fi
done
log "enabled rke2lab units"
