#!/usr/bin/env -S bash -exuo pipefail

: "Initialize Nix build group and users"

# Create nixbld group if it doesn't exist
if ! getent group nixbld &>/dev/null; then
  : "Creating nixbld group"
  groupadd --system nixbld
fi

# Create nixbld0 through nixbld31 users if they don't exist
for i in {0..31}; do
  username="nixbld${i}"
  if ! getent passwd "${username}" &>/dev/null; then
    : "Creating ${username}"
    useradd --system \
      --group nixbld \
      --home-dir /var/empty \
      --shell /run/current-system/sw/bin/nologin \
      --comment "Nix build user ${i}" \
      "${username}"
  fi
done

: "Nix build users initialized"
