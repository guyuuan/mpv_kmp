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
# macOS/iOS toolchain flags for configure/linker tests
darwin_target_flags=
if [ "$platform" = "macos" ] || [ "$platform" = "ios" ]; then
    darwin_target_flags="-target $target_triple"
    if [ -n "$SDKROOT" ] && [ -d "$SDKROOT" ]; then
        darwin_target_flags="$darwin_target_flags -isysroot $SDKROOT"
    fi
    # prefer a reasonable minimum to avoid SDK/ABI mismatches
    if [ "$platform" = "macos" ]; then
        darwin_target_flags="$darwin_target_flags -mmacosx-version-min=11.0"
    fi
fi
dav1d_flag="--disable-libdav1d"
if pkg-config --exists "dav1d >= 1.0.0"; then
    dav1d_flag="--enable-libdav1d"
fi

patch_ios_dylib_install_names() {
    [ "$platform" = "ios" ] || return 0

    local config_mak="ffbuild/config.mak"
    [ -f "$config_mak" ] || {
        echo "Missing FFmpeg config file: $config_mak" >&2
        return 1
    }

    perl -0pi -e 's|-Wl,-install_name,\$\(INSTALL_NAME_DIR\)/\$\(SLIBNAME_WITH_MAJOR\)|-Wl,-install_name,\@rpath/\$(SLIBNAME)|g; s|^SLIB_INSTALL_NAME=.*$|SLIB_INSTALL_NAME=\$(SLIBNAME)|m; s|^SLIB_INSTALL_LINKS=.*$|SLIB_INSTALL_LINKS=|m' "$config_mak"
}

args=()
case "$platform" in
    android)
        args=(
            --target-os=android --enable-cross-compile
            --cross-prefix=${target_triple}-
            --cc="$cc_bin" --pkg-config=pkg-config --nm=$NM
            --arch=${arch_family} --cpu=$cpu
            --extra-cflags="-I$prefix_dir/include $cpuflags $target_flags" --extra-ldflags="-L$prefix_dir/lib $target_flags"
            --enable-jni --enable-mediacodec --enable-mbedtls $dav1d_flag --disable-vulkan
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
            --cc="$cc_bin" --pkg-config=pkg-config --nm=$NM
            --arch=${arch_family}
            --extra-cflags="-I$prefix_dir/include $cpuflags $target_flags $darwin_target_flags -DHAVE_SYSCTL_H=0 -DHAVE_SYSCTL=0" --extra-ldflags="-L$prefix_dir/lib $target_flags $darwin_target_flags" --extra-libs="$addlibs"
            --enable-mbedtls $dav1d_flag --disable-vulkan
            --disable-static --enable-shared --enable-{gpl,version3}
            --disable-{stripping,doc}
            --disable-filter=gfxcapture
        )
        [ "$platform" = "ios" ] && args+=(--install-name-dir=@rpath)
    ;;
esac
configure_env=(
    CC="$cc_bin"
    LD="$cc_bin"
    AR="$AR"
    STRIP="$STRIP"
    NM="$NM"
    PKG_CONFIG="pkg-config"
    build_suffix=
)
if [ "$platform" = "ios" ]; then
    env -u SDKROOT "${configure_env[@]}" ../configure "${args[@]}"
else
    env "${configure_env[@]}" ../configure "${args[@]}"
fi
patch_ios_dylib_install_names
[ -f ffbuild/config.h ] && sed -i '' -e 's/^#define HAVE_SYSCTL_H 1/#define HAVE_SYSCTL_H 0/' -e 's/^#define HAVE_SYSCTL 1/#define HAVE_SYSCTL 0/' ffbuild/config.h || true
[ -f config.h ] && sed -i '' -e 's/^#define HAVE_SYSCTL_H 1/#define HAVE_SYSCTL_H 0/' -e 's/^#define HAVE_SYSCTL 1/#define HAVE_SYSCTL 0/' config.h || true

run_make() {
    if [ "$platform" = "ios" ]; then
        env -u SDKROOT make "$@"
    else
        make "$@"
    fi
}

run_make -j$cores
rm -f "$prefix_dir"/lib/libavcodec* "$prefix_dir"/lib/libavdevice* \
      "$prefix_dir"/lib/libavfilter* "$prefix_dir"/lib/libavformat* \
      "$prefix_dir"/lib/libavutil* "$prefix_dir"/lib/libswresample* \
      "$prefix_dir"/lib/libswscale* "$prefix_dir"/lib/pkgconfig/libavcodec* \
      "$prefix_dir"/lib/pkgconfig/libavdevice* "$prefix_dir"/lib/pkgconfig/libavfilter* \
      "$prefix_dir"/lib/pkgconfig/libavformat* "$prefix_dir"/lib/pkgconfig/libavutil* \
      "$prefix_dir"/lib/pkgconfig/libswresample* "$prefix_dir"/lib/pkgconfig/libswscale* \
      "$prefix_dir"/bin/avcodec* "$prefix_dir"/bin/avdevice* \
      "$prefix_dir"/bin/avfilter* "$prefix_dir"/bin/avformat* \
      "$prefix_dir"/bin/avutil* "$prefix_dir"/bin/swresample* \
      "$prefix_dir"/bin/swscale*
run_make DESTDIR="$prefix_dir" install
pcdir="$prefix_dir/lib/pkgconfig"
if [ -d "$pcdir" ]; then
  for pc in "$pcdir"/*-"$platform"-*.pc; do
      [ -e "$pc" ] || continue
      base=$(basename "$pc")
      plain="${base%%-$platform-*}.pc"
      echo "Linking $pc to $pcdir/$plain"
      [ -e "$pcdir/$plain" ] || ln -s "$base" "$pcdir/$plain"
  done
fi
