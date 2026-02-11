ifndef kpt.env.mk
kpt.env.mk := rke2.d/bioskop/master/kpt.env.mk
export KPT_MANIFESTS_DIR=/var/lib/git/nxmatic/rke2lab/rke2.d/bioskop/master/manifests.d
export KPT_CONFIG_DIR=/var/lib/git/nxmatic/rke2lab/.local.d/var/run/kpt/bioskop/master/runtime/rke2-config/configmaps
export KPT_RKE2_CONFIG_DEBUG=true
export KPT_RKE2_CONFIG_VERBOSITY=4
endif
