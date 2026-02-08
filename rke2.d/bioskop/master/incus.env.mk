ifndef incus.env.mk
incus.env.mk := rke2.d/bioskop/master/incus.env.mk
export INCUS_IMAGE_NAME=control-node
export INCUS_PROJECT_NAME=rke2lab
export INCUS_EGRESS_INTERFACE=vmlan0
export INCUS_RUNTIME_DIR=
export INCUS_WORKINGTREE_DIR=/var/lib/git/nxmatic/rke2lab
export INCUS_ENV_FILE=/var/lib/git/nxmatic/rke2lab/rke2.d/bioskop/master/environment
export INCUS_SHARED_DIR=/var/lib/git/nxmatic/rke2lab/.local.d/share
export INCUS_KUBECONFIG_DIR=/var/lib/git/nxmatic/rke2lab/.local.d/var/kube
export FLOX_CONFIG_DIR=/Users/nxmatic/.config/flox
export INCUS_NO_CLOUD_DIR=/var/lib/git/nxmatic/rke2lab/rke2.d/bioskop/master
export INCUS_IMAGE_BUILD_DIR=/var/lib/git/nxmatic/rke2lab/.local.d/var/lib/distrobuilder/control-node/rootfs
export INCUS_IMAGE_PACK_CONFIG=/var/lib/git/nxmatic/rke2lab/.local.d/var/lib/distrobuilder/control-node/pack.yaml
export INCUS_SECRETS_FILE=/var/lib/git/nxmatic/rke2lab/.local.d/var/private/incus/secrets
export INCUS_SYSTEMD_DIR=/var/lib/git/nxmatic/rke2lab/make.d/incus/systemd
export INCUS_SCRIPTS_DIR=/var/lib/git/nxmatic/rke2lab/make.d/incus/scripts
endif
