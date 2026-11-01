# Inherit the Pi hardware, TV provisioning, media stack and partition geometry.
$(call inherit-product, device/brcm/rpi5/aosp_rpi5_tv.mk)
PRODUCT_PACKAGES += VelaTv
PRODUCT_NAME := vela_rpi5_tv
PRODUCT_MODEL := Vela Pi 5
