# Inherited after the unmodified lineage_sif product configuration.
# Keep NVIDIA's device identity, partition geometry, kernel, HALs and SELinux.
PRODUCT_PACKAGES += VelaTv

# The inherited foster userdata size exceeds the 8 GB Tube's entire storage.
# Our recovery OTA never installs userdata; recovery formats the live UDA device.
# Do not emit a separate, incorrectly sized image that could be flashed later.
PRODUCT_BUILD_USERDATA_IMAGE := false
