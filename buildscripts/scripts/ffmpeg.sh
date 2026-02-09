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

mkdir -p _build${build_suffix}
cd _build${build_suffix}

arch_family=${target_triple%%-*}
cpu=generic
if [ "$platform" == "android" ]; then
    cpu=armv7-a
    [[ "$target_triple" == "aarch64"* ]] && cpu=armv8-a
    [[ "$target_triple" == "x86_64"* ]] && cpu=generic
    [[ "$target_triple" == "i686"* ]] && cpu="i686 --disable-asm"
fi

cpuflags=
[[ "$arch_family" == "arm" ]] && cpuflags="$cpuflags -mfpu=neon -mcpu=cortex-a8"

cc_bin="$CC"
target_flags=
if [ "$ZIG" = "1" ]; then
    target_flags="-target $ZIG_TARGET"
fi

args=()
case "$platform" in
    android)
        args=(
            --target-os=android --enable-cross-compile
            --cross-prefix=${target_triple}-
            --cc=$cc_bin --pkg-config=pkg-config --nm=$NM
            --arch=${arch_family} --cpu=$cpu
            --extra-cflags="-I$prefix_dir/include $cpuflags $target_flags" --extra-ldflags="-L$prefix_dir/lib $target_flags"
            --enable-{jni,mediacodec,mbedtls,libdav1d} --disable-vulkan
            --disable-static --enable-shared --enable-{gpl,version3}
            --disable-{stripping,doc,programs}
            --disable-{muxers,encoders,devices}
            --enable-encoder=mjpeg,png
            --enable-muxer=mov,matroska,mpegts
        )
    ;;
    macos|ios|linux|windows)
        target_os=linux
        [ "$platform" == "macos" ] && target_os=darwin
        [ "$platform" == "ios" ] && target_os=darwin
        [ "$platform" == "windows" ] && target_os=mingw32
        addlibs=
        if [ "$platform" == "windows" ]; then
            addlibs="-lmbedtls -lmbedx509 -lmbedcrypto -lws2_32 -lbcrypt"
        fi
        args=(
            --target-os=$target_os --enable-cross-compile
            --cc=$cc_bin --pkg-config=pkg-config --nm=$NM
            --arch=${arch_family}
            --extra-cflags="-I$prefix_dir/include $cpuflags $target_flags -DHAVE_SYSCTL_H=0 -DHAVE_SYSCTL=0" --extra-ldflags="-L$prefix_dir/lib $target_flags" --extra-libs="$addlibs"
            --enable-{mbedtls,libdav1d} --disable-vulkan
            --disable-static --enable-shared --enable-{gpl,version3}
            --disable-{stripping,doc}
            --disable-filter=gfxcapture
        )
    ;;
esac
CC="$cc_bin" LD="$cc_bin" AR="$AR" STRIP="$STRIP" NM="$NM" PKG_CONFIG="pkg-config" ../configure "${args[@]}"

make -j$cores
make DESTDIR="$prefix_dir" install
pcdir="$prefix_dir/lib/pkgconfig"
if [ -d "$pcdir" ]; then
    for pc in "$pcdir"/*-"$platform"-"$arch_family".pc; do
        [ -e "$pc" ] || continue
        base=$(basename "$pc")
        plain="${base%%-$platform-*}.pc"
        [ -e "$pcdir/$plain" ] || ln -s "$base" "$pcdir/$plain"
    done
fi
