#!/bin/bash -e

. ../../include/path.sh

if [ "$1" == "build" ]; then
	true
elif [ "$1" == "clean" ]; then
	make clean
	exit 0
else
	exit 255
fi

$0 clean # separate building not supported, always clean
if [[ "${target_triple:-}" == i686* ]]; then
	./scripts/config.py unset MBEDTLS_AESNI_C
else
	./scripts/config.py set MBEDTLS_AESNI_C
fi

# Disable Arm AESCE on non-Arm targets; enable only for Arm/aarch64
if [[ "${target_triple:-}" == aarch64* || "${target_triple:-}" == arm* ]]; then
	./scripts/config.py set MBEDTLS_AESCE_C
else
	./scripts/config.py unset MBEDTLS_AESCE_C
fi

make -C library -j$cores
mkdir -p "$prefix_dir/lib" "$prefix_dir/include"
cp -a library/libmbedcrypto.a library/libmbedtls.a library/libmbedx509.a "$prefix_dir/lib/"
cp -a include/* "$prefix_dir/include/"
