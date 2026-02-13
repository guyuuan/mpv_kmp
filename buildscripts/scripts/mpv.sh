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
pwd
echo "mpv output => $build"
echo "prefix_dir => $prefix_dir"
lua_opt="-Dlua=enabled"
[ "$platform" = "ios" ] && lua_opt="-Dlua=disabled"
ios_audio_opts=
[ "$platform" = "ios" ] && ios_audio_opts="-Daudiounit=disabled -Davfoundation=disabled -Dcoreaudio=disabled -Dios-gl=disabled -Dvideotoolbox-gl=disabled"
meson setup $build --cross-file "$prefix_dir"/crossfile.txt \
	--default-library shared \
	-Diconv=disabled $lua_opt $ios_audio_opts \
	-Dlibmpv=true -Dcplayer=false \
    -Dmanpage-build=disabled -Dswift-build=disabled \
    -Dmacos-cocoa-cb=disabled -Dmacos-media-player=disabled -Dmacos-touchbar=disabled \
    -Dgl-cocoa=disabled -Dplain-gl=enabled \
    -Dc_args=-DNO_BUILD_TIMESTAMPS \
    -Dcocoa=disabled \
    $( [ "$cross_system" = "windows" ] && echo "-Dzlib=disabled" )

ninja -C $build -j$cores
if [ -f $build/libmpv.a ]; then
	echo >&2 "Meson fucked up, forcing rebuild."
	# $0 clean
	exec $0 build
fi
DESTDIR="$prefix_dir" ninja -C $build install
