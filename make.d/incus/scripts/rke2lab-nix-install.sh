#!/usr/bin/env -S bash -exuo pipefail

: "Install Nix package manager and configure system-wide availability"

export HOME=/root

# Install Nix using official installer
bash -exuo pipefail <(curl --proto '=https' --tlsv1.2 -L https://nixos.org/nix/install) --daemon

# Verify Nix installation created /etc/nix directory
if [ ! -d /etc/nix ]; then
  echo "ERROR: Nix installer did not create /etc/nix directory" >&2
  exit 1
fi

# Configure Nix after installation (idempotent - only if not already configured by rke2lab)
if ! grep -q "BEGIN rke2lab-nix" /etc/nix/nix.conf 2>/dev/null; then
  cat >> /etc/nix/nix.conf <<'EOF'
# BEGIN rke2lab-nix: Custom configuration for rke2lab environment
allowed-users = *
auto-optimise-store = false
builders = 
cores = 0
experimental-features = nix-command flakes ca-derivations
max-jobs = 4
require-sigs = true
sandbox = false
sandbox-fallback = false
substituters = https://aseipp-nix-cache.freetls.fastly.net https://nxmatic.cachix.org https://cache.nixos.org/
system-features = nixos-test benchmark big-parallel kvm gccarch-armv8-a
trusted-public-keys = cache.nixos.org-1:6NCHdD59X431o0gWypbMrAURkbJ16ZPMQFGspcDShjY= cache.nixos.org-1:6NCHdD59X431o0gWypbMrAURkbJ16ZPMQFGspcDShjY= nxmatic.cachix.org-1:huMghYiwDpPa1PMXHXK4G1Dp4QOZjgsNqxcjf/AjuJ0= cache.nixos.org-1:6NCHdD59X431o0gWypbMrAURkbJ16ZPMQFGspcDShjY=
trusted-substituters = https://cache.nixos.org
trusted-users = root nxmatic root nxmatic root @admin @wheel
extra-platforms = x86_64-linux x86_64-linux
extra-sandbox-paths = /run/binfmt /nix/store/7k1f2qca1mxyrzl6wr74dilrhwbx6qvs-qemu-x86_64-binfmt-P /dev/kvm
keep-outputs = false
keep-derivations = false
keep-failed = false
# END rke2lab-nix
EOF
fi

# Create /etc/profile.d/nix-profile.sh for login shells and BASH_ENV
cat > /etc/profile.d/nix-profile.sh <<'EOF'
#!/bin/bash
# Initialize Nix profile for daemon installation
if [ -e /nix/var/nix/profiles/default/etc/profile.d/nix-daemon.sh ]; then
  . /nix/var/nix/profiles/default/etc/profile.d/nix-daemon.sh
fi
EOF
chmod 755 /etc/profile.d/nix-profile.sh

: "Enable /etc/profile.d sourcing in /etc/bash.bashrc for interactive shells"
if ! grep -q "BEGIN rke2lab-nix" /etc/bash.bashrc; then
  cat >> /etc/bash.bashrc <<'EOF'

# BEGIN rke2lab-nix: Source /etc/profile.d scripts for interactive shells
# (Debian default has this commented out, we enable it)
if [ -d /etc/profile.d ]; then
  for nix_profile_script in /etc/profile.d/*.sh; do
    if [ -r "$nix_profile_script" ]; then
      . "$nix_profile_script"
    fi
  done
  unset nix_profile_script
fi
# END rke2lab-nix
EOF
fi

: "Set BASH_ENV in /etc/environment for non-interactive shells"
if ! grep -q "^BASH_ENV=" /etc/environment 2>/dev/null; then
  echo "BASH_ENV=/etc/profile.d/nix-profile.sh" >> /etc/environment
fi

: "Set BASH_ENV in systemd environment"
mkdir -p /etc/systemd/system.conf.d
if [ ! -f /etc/systemd/system.conf.d/10-rke2lab-nix.conf ]; then
  cat > /etc/systemd/system.conf.d/10-rke2lab-nix.conf <<'EOF'
[Manager]
DefaultEnvironment="BASH_ENV=/etc/profile.d/nix-profile.sh"
EOF
  : "Reload systemd configuration to apply new environment"
  systemctl daemon-reexec
fi

