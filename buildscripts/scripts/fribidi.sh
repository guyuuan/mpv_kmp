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

if [ "$platform" = "ios" ] &&
	[ -n "$meson_native_file" ] &&
	[ -f "$meson_native_file" ] &&
	[ -f "$build/build.ninja" ] &&
	grep -q "'-u','SDKROOT'" "$meson_native_file" &&
	grep -q '^ command = cc ' "$build/build.ninja"; then
	rm -rf "$build"
fi

meson_setup_args=("$build")
[ -f "$build/meson-private/coredata.dat" ] && meson_setup_args+=(--reconfigure)
[ -n "$meson_native_file" ] && meson_setup_args+=(--native-file "$meson_native_file")
meson setup "${meson_setup_args[@]}" --cross-file "$prefix_dir"/crossfile.txt \
	-D{tests,docs}=false

ninja -C $build -j$cores
DESTDIR="$prefix_dir" ninja -C $build install
