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

unset CC CXX # meson wants these unset
opts=
[ "$cross_system" = "windows" ] && opts="-Dzlib=none -Dbzip2=disabled -Dpng=disabled -Dbrotli=disabled"
[ "$cross_system" = "linux" ] && ! pkg-config --exists zlib && opts="$opts -Dzlib=disabled"
meson setup $build --cross-file "$prefix_dir"/crossfile.txt $opts

ninja -C $build -j$cores
DESTDIR="$prefix_dir" ninja -C $build install
