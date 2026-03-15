#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <manifest-file> <manifest-dir>" >&2
  exit 2
fi

manifest_file="$1"
manifest_dir="$2"

if [[ ! -f "$manifest_file" ]]; then
  echo "Manifest file not found: $manifest_file" >&2
  exit 1
fi

rm -rf "$manifest_dir"
mkdir -p "$manifest_dir"

cd "$manifest_dir"

yq --split-exp='(
(.metadata.annotations["kpt.dev/package-layer"] // "default") + "/" +
(.metadata.annotations["kpt.dev/package-name"] // "unknown") + "/" +
("00-" + (.kind | downcase) + "-" + (
  .metadata.name
  | downcase
  | sub(":"; "-")
  | sub("/"; "-")
)) +
".yml"
)' eval-all 'select(.kind == "CustomResourceDefinition")' "$manifest_file"

yq --split-exp='(
(.metadata.annotations["kpt.dev/package-layer"] // "default") + "/" +
(.metadata.annotations["kpt.dev/package-name"] // "unknown") + "/" +
("01-" + (.kind | downcase) + "-" + (
  .metadata.name
  | downcase
  | sub(":"; "-")
  | sub("/"; "-")
)) +
".yml"
)' eval-all 'select(.kind != "CustomResourceDefinition" and (.metadata.namespace == null or .metadata.namespace == ""))' "$manifest_file"

yq --split-exp='(
(.metadata.annotations["kpt.dev/package-layer"] // "default") + "/" +
(.metadata.annotations["kpt.dev/package-name"] // "unknown") + "/" +
("02-" + (.kind | downcase) + "-" + (
  .metadata.name
  | downcase
  | sub(":"; "-")
  | sub("/"; "-")
)) +
".yml"
)' eval-all 'select(.metadata.namespace != null and .metadata.namespace != "")' "$manifest_file"
