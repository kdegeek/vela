#!/bin/bash
# Usage: press.sh key [key...]   keys: up down left right ok back home hold-home
# Presses remote keys through a virtual HID remote (the shell's `hid` command), so they take the same path as a real
# remote and reach accessibility services. `adb shell input keyevent` does not. Set ANDROID_SERIAL to pick a device.
ADB="${ADB:-adb} ${ANDROID_SERIAL:+-s $ANDROID_SERIAL}"
f=$(mktemp)
echo '{"id":1,"command":"register","name":"Vela test remote","vid":4660,"pid":22136,"bus":"usb","descriptor":[5,1,9,6,161,1,133,1,5,7,25,224,41,231,21,0,37,1,117,1,149,8,129,2,149,1,117,8,129,1,149,6,117,8,21,0,37,101,5,7,25,0,41,101,129,0,192,5,12,9,1,161,1,133,2,21,0,38,255,3,25,0,42,255,3,117,16,149,1,129,0,192]}' > "$f"
echo '{"id":1,"command":"delay","duration":1200}' >> "$f"
for k in "$@"; do
  case $k in
    up) r='[1,0,0,82,0,0,0,0,0]'; z='[1,0,0,0,0,0,0,0,0]';;
    down) r='[1,0,0,81,0,0,0,0,0]'; z='[1,0,0,0,0,0,0,0,0]';;
    left) r='[1,0,0,80,0,0,0,0,0]'; z='[1,0,0,0,0,0,0,0,0]';;
    right) r='[1,0,0,79,0,0,0,0,0]'; z='[1,0,0,0,0,0,0,0,0]';;
    ok) r='[1,0,0,40,0,0,0,0,0]'; z='[1,0,0,0,0,0,0,0,0]';;
    home) r='[2,35,2]'; z='[2,0,0]';;
    back) r='[2,36,2]'; z='[2,0,0]';;
    hold-home) r='[2,35,2]'; z='[2,0,0]'; echo "{\"id\":1,\"command\":\"report\",\"report\":$r}" >> "$f"; echo '{"id":1,"command":"delay","duration":900}' >> "$f"; echo "{\"id\":1,\"command\":\"report\",\"report\":$z}" >> "$f"; echo '{"id":1,"command":"delay","duration":500}' >> "$f"; continue;;
  esac
  echo "{\"id\":1,\"command\":\"report\",\"report\":$r}" >> "$f"
  echo '{"id":1,"command":"delay","duration":80}' >> "$f"
  echo "{\"id\":1,\"command\":\"report\",\"report\":$z}" >> "$f"
  echo '{"id":1,"command":"delay","duration":500}' >> "$f"
done
$ADB push "$f" /data/local/tmp/keys.json >/dev/null && $ADB shell hid /data/local/tmp/keys.json >/dev/null 2>&1
