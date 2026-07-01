#!/bin/bash -e

. ../../include/path.sh

build=_build${build_suffix}

patch_macos_coreaudio_utils_source() {
    [ "$platform" = "macos" ] || return 0

    local ninja_file="$build/build.ninja"
    local obj="libmpv.2.dylib.p/osdep_utils-mac.c.o"
    local anchor_obj="libmpv.2.dylib.p/audio_out_ao_coreaudio_utils.c.o"

    [ -f "$ninja_file" ] || return 0
    grep -q "$anchor_obj" "$ninja_file" || return 0
    grep -q "$obj" "$ninja_file" && return 0

    local anchor_line order_only args_line
    anchor_line=$(grep -m1 "^build $anchor_obj:" "$ninja_file")
    order_only="${anchor_line#*../audio/out/ao_coreaudio_utils.c }"
    args_line=$(awk -v target="$anchor_obj" '
        $0 ~ "^build " target ":" { found = 1; next }
        found && /^ ARGS = / { print; exit }
    ' "$ninja_file")

    [ -n "$args_line" ] || {
        echo "Failed to locate mpv compile args for $anchor_obj" >&2
        return 1
    }

    {
        printf "\nbuild %s: c_COMPILER ../osdep/utils-mac.c %s\n" "$obj" "$order_only"
        printf " DEPFILE = %s.d\n" "$obj"
        printf " DEPFILE_UNQUOTED = %s.d\n" "$obj"
        printf "%s\n" "$args_line"
    } >> "$ninja_file"

    perl -0pi -e "s|(build libmpv\\.2\\.dylib: c_LINKER (?:(?!\\n).)*?)libmpv\\.2\\.dylib\\.p/osdep_path-darwin\\.c\\.o|\\1$obj libmpv.2.dylib.p/osdep_path-darwin.c.o|" "$ninja_file"
    grep -q "$obj libmpv.2.dylib.p/osdep_path-darwin.c.o" "$ninja_file" || {
        echo "Failed to add osdep/utils-mac.c to libmpv link inputs" >&2
        return 1
    }
}

patch_ios_macos_sdk_link_args() {
    [ "$platform" = "ios" ] || return 0

    local ninja_file="$build/build.ninja"
    [ -f "$ninja_file" ] || return 0

    perl -0pi -e 's/ -isysroot \S*MacOSX\S*//g; s/ -L\/usr\/lib//g; s/ -L\/usr\/local\/lib//g; s/-install_name (?:\@rpath|\/usr\/local\/lib)\/libmpv\.[0-9]+\.dylib/-install_name \@rpath\/libmpv.dylib/g' "$ninja_file"
}

install_ios_unversioned_mpv_dylib() {
    [ "$platform" = "ios" ] || return 0

    local built_dylib="$build/libmpv.2.dylib"
    local install_dylib="$prefix_dir/lib/libmpv.dylib"
    [ -f "$built_dylib" ] || {
        echo "Missing built iOS libmpv dylib: $built_dylib" >&2
        return 1
    }

    rm -f "$install_dylib"
    cp -f "$built_dylib" "$install_dylib"
}

prepare_ios_mpv_install_dir() {
    [ "$platform" = "ios" ] || return 0

    rm -f "$prefix_dir/lib/libmpv.dylib"
}

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
[ "$platform" = "ios" ] && ios_audio_opts="-Daudiounit=disabled -Davfoundation=disabled -Dcoreaudio=disabled -Dios-gl=enabled -Dvideotoolbox-gl=disabled"
ios_macos_opts=
[ "$platform" = "ios" ] && ios_macos_opts="-Dmacos-10-15-4-features=disabled -Dmacos-11-features=disabled -Dmacos-11-3-features=disabled -Dmacos-12-features=disabled"
macos_jvm_opts="-Dcoreaudio=disabled -Davfoundation=disabled -Djack=disabled -Dcocoa=disabled -Dswift-build=disabled"
[ "$platform" = "macos" ] && macos_jvm_opts="-Dcoreaudio=enabled -Davfoundation=disabled -Djack=disabled -Dcocoa=enabled -Dswift-build=enabled -Dvideotoolbox-gl=enabled"
android_link_opts=
[ "$platform" = "android" ] && android_link_opts="-Dc_link_args=-lc++_shared"
meson_setup_args=("$build")
[ -f "$build/meson-private/coredata.dat" ] && meson_setup_args+=(--reconfigure --clearcache)
[ -n "$meson_native_file" ] && meson_setup_args+=(--native-file "$meson_native_file")
meson setup "${meson_setup_args[@]}" --cross-file "$prefix_dir"/crossfile.txt \
	--default-library shared \
	-Diconv=disabled $lua_opt $ios_audio_opts \
    $ios_macos_opts \
	-Dlibmpv=true -Dcplayer=false \
    $macos_jvm_opts \
    -Dlibavdevice=disabled -Dlibbluray=disabled -Drubberband=disabled \
    -Dlcms2=disabled -Dzimg=disabled -Djpeg=disabled \
    -Dx11=disabled -Dx11-clipboard=disabled \
    -Dmanpage-build=disabled \
    -Dmacos-cocoa-cb=disabled -Dmacos-media-player=disabled -Dmacos-touchbar=disabled \
    -Dgl-cocoa=enabled -Dplain-gl=enabled \
    -Dc_args=-DNO_BUILD_TIMESTAMPS \
    $android_link_opts \
    $( [ "$cross_system" = "windows" ] && echo "-Dzlib=disabled" )

patch_macos_coreaudio_utils_source
patch_ios_macos_sdk_link_args

ninja -C $build -j$cores
if [ -f $build/libmpv.a ]; then
	echo >&2 "Meson fucked up, forcing rebuild."
	# $0 clean
	exec $0 build
fi
prepare_ios_mpv_install_dir
DESTDIR="$prefix_dir" ninja -C $build install
install_ios_unversioned_mpv_dylib
