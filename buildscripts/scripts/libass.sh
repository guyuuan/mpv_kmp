#!/bin/bash -e

. ../../include/path.sh

if [ "$1" == "build" ]; then
	true
elif [ "$1" == "clean" ]; then
    rm -rf _build${build_suffix}
	exit 0
else
	exit 255
fi

[ -f configure ] || ./autogen.sh

mkdir -p _build${build_suffix}
cd _build${build_suffix}

arch_family=${target_triple%%-*}
host_triple=${target_triple:-$ndk_triple}
[ "$platform" = "ios" ] && [ "$arch_family" = "x86_64" ] && host_triple="x86_64-apple-darwin"
[ "$platform" = "ios" ] && { [ "$arch_family" = "arm64" ] || [ "$arch_family" = "aarch64" ]; } && host_triple="aarch64-apple-darwin"

../configure \
    --host=$host_triple --with-pic \
	--enable-static --disable-shared \
	--enable-libunibreak --disable-require-system-font-provider

make -j$cores
make DESTDIR="$prefix_dir" install
