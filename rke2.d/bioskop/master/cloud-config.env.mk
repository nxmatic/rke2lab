ifndef cloud-config.env.mk
cloud-config.env.mk := rke2.d/bioskop/master/cloud-config.env.mk

export CLOUDCONFIG_METADATA_FILE := /var/lib/git/nxmatic/rke2lab/rke2.d/bioskop/master/meta-data
export CLOUDCONFIG_USERDATA_FILE := /var/lib/git/nxmatic/rke2lab/rke2.d/bioskop/master/user-data
export CLOUDCONFIG_NETCFG_FILE := /var/lib/git/nxmatic/rke2lab/rke2.d/bioskop/master/network-config

endif
