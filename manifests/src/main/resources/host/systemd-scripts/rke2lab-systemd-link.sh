#!/usr/bin/env -S bash -exu -o pipefail

: "Load RKE2 environment" # @codebase
source /srv/host/systemd-scripts.d/rke2lab-env-load.sh

log() {
	printf '[systemd-link] %s\n' "$*"
}

SYSTEMD_UNITS_DIR="${RKE2LAB_SYSTEMD_DIR}"

# Wait for the bind-mount to appear and contain files (up to 30s)
for i in {1..30}; do
	if [[ -d "${SYSTEMD_UNITS_DIR}" && $(find "${SYSTEMD_UNITS_DIR}" -type f | wc -l) -gt 0 ]]; then
		break
	fi
	log "waiting for ${SYSTEMD_UNITS_DIR} to be populated (attempt ${i}/30)"
	sleep 1
done

if [[ ! -d "${SYSTEMD_UNITS_DIR}" ]]; then
	log "systemd units directory not found: ${SYSTEMD_UNITS_DIR}"
	exit 1
fi

mkdir -p /etc/systemd/system

link_units_fallback() {
	log "xstow unavailable; using fallback symlink linker"
	while IFS= read -r -d '' src; do
		rel="${src#${SYSTEMD_UNITS_DIR}/}"
		dst="/etc/systemd/system/${rel}"
		mkdir -p "$(dirname "${dst}")"
		ln -sfn "${src}" "${dst}"
	done < <(find "${SYSTEMD_UNITS_DIR}" -type f -print0)
	log "fallback linked systemd units from ${SYSTEMD_UNITS_DIR}"
}

: "Stow unit tree from host/systemd-units into /etc/systemd/system"
if ! command -v xstow >/dev/null 2>&1; then
	: "Install xstow for stowing systemd unit files"
	if ! flox install --dir="${FLOX_ENV_PROJECT}" xstow; then
		log "warning: failed to install xstow via Flox; proceeding with fallback linker"
	fi
fi
if command -v xstow >/dev/null 2>&1; then
	xstow -v=3 \
		-d "$(dirname "${SYSTEMD_UNITS_DIR}")" \
		-t /etc/systemd/system \
		"$(basename "${SYSTEMD_UNITS_DIR}")"
	log "stowed systemd units from ${SYSTEMD_UNITS_DIR}"
else
	link_units_fallback
fi

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
