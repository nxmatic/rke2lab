#!/bin/bash

# Define the directory containing the keys and the output YAML file
: "${KEYS_DIR:="keys.d"}"

key:private() {
    local file="${1}"
    cat "${file}"
}

key:public() {
    local file="${1}.pub"
    read -r format key email <"${file}"
    cat <<EOE
format: "${format}"
key: "${key}"
email: "${email}"
EOE
}

key:indent() {
    sed -e 's/^/      /' /dev/stdin
}

# Add each key pair to the YAML file
for key in "$KEYS_DIR"/*; do
    if [[ ! ( -f "$key" && "${key##*.}" != "pub" ) ]]; then
        continue
    fi
    cat <<EOE
keys:
  - private: |
$( key:private "${key}" | key:indent )
    public:
$( key:public "${key}" | key:indent )
EOE
done