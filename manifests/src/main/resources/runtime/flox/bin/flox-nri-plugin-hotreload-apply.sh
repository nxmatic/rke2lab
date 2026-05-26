#!/usr/bin/env bash
set -euo pipefail

# Flox NRI Plugin Hot-Reload Dev Tool
#
# Packages a freshly-built NRI plugin binary into the dynamic ConfigMap format
# and applies it to the cluster via kubectl.
#
# Usage:
#   flox-nri-plugin-hotreload-apply.sh [--namespace <ns>] [--plugin-binary <path>]
#
# Options:
#   --namespace <ns>        Target namespace (default: rke2lab-system)
#   --plugin-binary <path>  Path to plugin binary (default: auto-detect from flake)
#   --help                  Show this help

usage() {
	cat <<-EOF
		Usage: $0 [OPTIONS]

		Package and apply NRI plugin hot-reload ConfigMap.

		OPTIONS:
		  --namespace <ns>        Target namespace (default: rke2lab-system)
		  --plugin-binary <path>  Path to plugin binary (default: auto-detect)
		  --help                  Show this help

		EXAMPLES:
		  # Auto-detect plugin from flake, apply to rke2lab-system:
		  $0

		  # Use explicit binary path:
		  $0 --plugin-binary /nix/store/.../bin/flox-nri-plugin

		  # Apply to different namespace:
		  $0 --namespace my-namespace
	EOF
}

NAMESPACE="rke2lab-system"
PLUGIN_BINARY=""
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FLOX_RUNTIME_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

while [[ $# -gt 0 ]]; do
	case "$1" in
	--namespace)
		NAMESPACE="$2"
		shift 2
		;;
	--plugin-binary)
		PLUGIN_BINARY="$2"
		shift 2
		;;
	--help)
		usage
		exit 0
		;;
	*)
		echo "Unknown option: $1" >&2
		usage >&2
		exit 1
		;;
	esac
done

echo "==> Flox NRI Plugin Hot-Reload Tool"
echo "    Namespace: ${NAMESPACE}"
echo "    Flox runtime root: ${FLOX_RUNTIME_ROOT}"

# Auto-detect plugin binary from flake if not explicitly provided
if [[ -z "${PLUGIN_BINARY}" ]]; then
	echo "==> Auto-detecting plugin binary from flake..."
	if [[ ! -f "${FLOX_RUNTIME_ROOT}/flake.nix" ]]; then
		echo "ERROR: flake.nix not found at ${FLOX_RUNTIME_ROOT}/flake.nix" >&2
		exit 1
	fi

	# Build the debug variant by default (matches the bootstrap installer behavior
	# when RKE2LAB_POLICY_DEBUG_NRI_PLUGINS_FLOX_ENABLED=true)
	PLUGIN_BINARY="$(nix build --no-link --print-out-paths "${FLOX_RUNTIME_ROOT}#flox-nri-plugin-debug")/bin/flox-nri-plugin"
	echo "    Detected: ${PLUGIN_BINARY}"
fi

[[ -x "${PLUGIN_BINARY}" ]] || {
	echo "ERROR: Plugin binary not found or not executable: ${PLUGIN_BINARY}" >&2
	exit 1
}

# Create temporary workspace for archive generation
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "${WORK_DIR}"' EXIT

ARCHIVE_DIR="${WORK_DIR}/nri-plugin.dyn"
mkdir -p "${ARCHIVE_DIR}/bin"

echo "==> Packaging plugin binary..."
install -m 0755 "${PLUGIN_BINARY}" "${ARCHIVE_DIR}/bin/flox-nri-plugin"

# Generate archive manifest (JSON with per-file checksums)
MANIFEST_FILE="${WORK_DIR}/nri-plugin.manifest.json"
{
	echo "{"
	echo "  \"archive\": {"
	# Archive-level checksum will be computed after tar creation
	echo "    \"size\": 0,"
	echo "    \"sha256\": \"placeholder\""
	echo "  },"
	echo "  \"entries\": ["

	FIRST=true
	while IFS= read -r -d '' file_path; do
		relative_path="${file_path#${ARCHIVE_DIR}/}"
		file_size="$(wc -c <"${file_path}" | tr -d '[:space:]')"
		file_sha256="$(sha256sum "${file_path}" | awk '{print $1}')"

		[[ "${FIRST}" == "true" ]] || echo "    ,"
		FIRST=false

		cat <<-EOF_ENTRY
			    {
			      "path": "${relative_path}",
			      "size": ${file_size},
			      "sha256": "${file_sha256}"
			    }
		EOF_ENTRY
	done < <(find "${ARCHIVE_DIR}" -type f -print0 | sort -z)

	echo "  ]"
	echo "}"
} >"${MANIFEST_FILE}.tmp"

# Create tar archive
TAR_FILE="${WORK_DIR}/nri-plugin.tar"
tar -C "${WORK_DIR}" -cf "${TAR_FILE}" nri-plugin.dyn

# Compute archive checksum and finalize manifest
ARCHIVE_SIZE="$(wc -c <"${TAR_FILE}" | tr -d '[:space:]')"
ARCHIVE_SHA256="$(sha256sum "${TAR_FILE}" | awk '{print $1}')"

jq --arg size "${ARCHIVE_SIZE}" --arg sha256 "${ARCHIVE_SHA256}" \
	'.archive.size = ($size | tonumber) | .archive.sha256 = $sha256' \
	"${MANIFEST_FILE}.tmp" >"${MANIFEST_FILE}"

echo "    Archive size: ${ARCHIVE_SIZE} bytes"
echo "    Archive SHA256: ${ARCHIVE_SHA256}"

# Base64-encode the tar archive
TAR_B64_FILE="${WORK_DIR}/nri-plugin.tar.b64"
base64 <"${TAR_FILE}" >"${TAR_B64_FILE}"

echo "==> Generating ConfigMap YAML..."
CONFIGMAP_YAML="${WORK_DIR}/flox-nri-plugin-dyn.yaml"

cat >"${CONFIGMAP_YAML}" <<EOF
apiVersion: v1
kind: ConfigMap
metadata:
  name: flox-nri-plugin-dyn
  namespace: ${NAMESPACE}
data:
  nri-plugin.tar.b64: |
$(sed 's/^/    /' "${TAR_B64_FILE}")
  nri-plugin.manifest.json: |
$(sed 's/^/    /' "${MANIFEST_FILE}")
EOF

echo "==> Applying ConfigMap to cluster..."
kubectl apply -f "${CONFIGMAP_YAML}"

echo ""
echo "==> Hot-reload ConfigMap applied successfully!"
echo "    The reconciler sidecar on each node will detect the change within ~30-60s,"
echo "    materialize the new plugin binary, and restart the running plugin process."
echo ""
echo "    Monitor reconciler logs with:"
echo "      kubectl logs -n ${NAMESPACE} -l app.kubernetes.io/name=flox-runtime-installer -c reconciler -f"
echo ""
echo "    Check plugin restart with:"
echo "      kubectl logs -n ${NAMESPACE} -l app.kubernetes.io/name=flox-runtime-installer -c main --tail=20"
