#!/usr/bin/env bash
# The source SDK launcher expects an installed-SDK JAR layout. Use pinned inputs.
set -euo pipefail
source_dir=${VELA_ANDROID_SOURCE:?Set VELA_ANDROID_SOURCE to the locked Android checkout}
java=$source_dir/prebuilts/jdk/jdk21/linux-x86/bin/java
jar=$source_dir/prebuilts/sdk/tools/linux/lib/apksigner.jar
if [[ ! -d $source_dir/.repo || ! -x $java || ! -f $jar ]]; then
    echo 'Missing pinned Android checkout JDK or SDK apksigner JAR.' >&2
    exit 2
fi
exec "$java" -jar "$jar" "$@"
