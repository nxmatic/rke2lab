#!/usr/bin/env -S bash -exu -o pipefail

: "Activate flox environment"
source <(flox activate --dir /var/lib/rancher/rke2)

: "Link committed RKE2 manifests from RKE2LAB_MANIFESTS_DIR into RKE2 server manifests directory"
MANIFESTS_DIR=/var/lib/rancher/rke2/server/manifests

: "Ensure RKE2 systemd units are visible to systemd"
SRC_UNIT_DIR="/usr/local/lib/systemd/system"
DEST_UNIT_DIR="/etc/systemd/system"

log() {
	printf '[rke2-install-post] %s\n' "$*"
}

bool_is_true() {
	case "${1:-}" in
	1 | true | TRUE | yes | YES | on | ON)
		return 0
		;;
	*)
		return 1
		;;
	esac
}

policy_link_var_name() {
	local layer_key="${1:?layer key required}"
	printf 'RKE2LAB_POLICY_LINK_%s_ENABLED\n' "$(printf '%s' "${layer_key}" | tr '[:lower:]-/' '[:upper:]__')"
}

link_layer_if_enabled() {
	local source_rel="${1:?source path required}"
	local target_rel="${2:?target path required}"
	local layer_key="${3:?layer key required}"
	local default_enabled="${4:?default required}"
	local var_name enabled_value source_path target_path

	var_name="$(policy_link_var_name "${layer_key}")"
	enabled_value="${!var_name:-${default_enabled}}"
	source_path="${RKE2LAB_MANIFESTS_DIR}/${source_rel}"
	target_path="${MANIFESTS_DIR}/${target_rel}"

	if bool_is_true "${enabled_value}"; then
		if [[ -e "${source_path}" ]]; then
			mkdir -p "$(dirname "${target_path}")"
			ln -sfn "${source_path}" "${target_path}"
			log "linked layer ${layer_key}: ${target_rel} -> ${source_rel}"
		else
			log "layer ${layer_key} enabled but source missing: ${source_path}"
		fi
		return 0
	fi

	if [[ -L "${target_path}" || -e "${target_path}" ]]; then
		rm -rf "${target_path}"
		log "removed live manifests for disabled layer ${layer_key}: ${target_rel}"
	else
		log "layer ${layer_key} disabled; host manifests kept at ${source_path}"
	fi
}

link_layer_if_enabled "high-availability" "high-availability" "high-availability" "true"

: "Do not pre-link post-server layer trees into the live RKE2 manifests directory here."
: "Those layers are installed by dedicated rke2lab-*-manifests services that impose their own"
: "host-side ordering and readiness gates after the API server is up. Pre-linking them here lets"
: "rke2-server observe lifecycle-dependent manifests too early, before prerequisites such as the"
: "rke2lab-system namespace or Cilium/Gateway API CRDs exist."

if [[ ! -d "$SRC_UNIT_DIR" ]]; then
	log "source unit dir missing: $SRC_UNIT_DIR"
	exit 0
fi

mkdir -p "$DEST_UNIT_DIR"

found=0
for unit in "$SRC_UNIT_DIR"/rke2-*.service; do
	[[ -f "$unit" ]] || continue
	found=1
	base=$(basename "$unit")
	dest="$DEST_UNIT_DIR/$base"
	ln -sf "$unit" "$dest"
	log "linked $base"
done

if [[ $found -eq 0 ]]; then
	log "no rke2 unit files found in $SRC_UNIT_DIR"
fi

systemctl daemon-reload
log "daemon-reload complete"
