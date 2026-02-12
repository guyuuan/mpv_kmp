#!/bin/bash -e

. ../../include/path.sh

build=_build${build_suffix}

if [ "$1" == "build" ]; then
	true
elif [ "$1" == "clean" ]; then
	rm -rf $build
	exit 0
else
	exit 255
fi

mkdir -p $build
cd $build

arch_family=${target_triple%%-*}
host_triple=${target_triple:-$ndk_triple}
[ "$platform" = "ios" ] && [ "$arch_family" = "x86_64" ] && host_triple="x86_64-apple-darwin"
[ "$platform" = "ios" ] && { [ "$arch_family" = "arm64" ] || [ "$arch_family" = "aarch64" ]; } && host_triple="aarch64-apple-darwin"

../configure \
	--host=$host_triple --with-pic \
	--enable-static --disable-shared

make -j$cores
make DESTDIR="$prefix_dir" install
