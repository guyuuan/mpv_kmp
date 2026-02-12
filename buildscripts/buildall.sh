#!/bin/bash -e

cd "$( dirname "${BASH_SOURCE[0]}" )"
. ./include/depinfo.sh

cleanbuild=0
nodeps=0
clang=1
target=mpv
arch=armv7l
platform=android
export platform

getdeps () {
	varname="dep_${1//-/_}[*]"
	echo ${!varname}
}

load_target () {
	unset CC CXX CPATH LIBRARY_PATH C_INCLUDE_PATH CPLUS_INCLUDE_PATH
	unset CFLAGS CXXFLAGS CPPFLAGS LDFLAGS
    export AR=llvm-ar
    export RANLIB=llvm-ranlib
    export NM=llvm-nm
    export STRIP=llvm-strip
    if ! command -v "$AR" >/dev/null 2>&1; then
        case "$platform" in
            macos|ios|linux)
                export AR=ar
                export RANLIB=ranlib
                export NM=nm
                export STRIP=strip
            ;;
        esac
    fi
    export build_suffix="-$platform-$arch"

    case "$platform" in
        android)
            local apilvl=21
            # ndk_triple: what the toolchain actually is
            # cc_triple: what Google pretends the toolchain is
            if [ "$1" == "armv7l" ]; then
                export ndk_suffix=
                export target_triple=arm-linux-androideabi
                cc_triple=armv7a-linux-androideabi$apilvl
                prefix_name=android-armv7l
            elif [ "$1" == "arm64" ]; then
                export ndk_suffix=-arm64
                export target_triple=aarch64-linux-android
                cc_triple=$target_triple$apilvl
                prefix_name=android-arm64
            elif [ "$1" == "x86" ]; then
                export ndk_suffix=-x86
                export target_triple=i686-linux-android
                cc_triple=$target_triple$apilvl
                prefix_name=android-x86
            elif [ "$1" == "x86_64" ]; then
                export ndk_suffix=-x64
                export target_triple=x86_64-linux-android
                cc_triple=$target_triple$apilvl
                prefix_name=android-x86_64
            else
                echo "Invalid android architecture" >&2
                exit 1
            fi
            if [ $clang -eq 1 ]; then
                export CC=$cc_triple-clang
                export CXX=$cc_triple-clang++
            else
                export CC=$cc_triple-gcc
                export CXX=$cc_triple-g++
            fi
            export cross_system=android
            export LDFLAGS="-Wl,-O1,--icf=safe -Wl,-z,max-page-size=16384"
        ;;
        macos)
            case "$1" in
                arm64)
                    export target_triple=arm64-apple-darwin
                    prefix_name=macos-arm64
                ;;
                x86_64)
                    export target_triple=x86_64-apple-darwin
                    prefix_name=macos-x86_64
                ;;
                universal)
                    # handled specially later; still set a prefix for output merge
                    export target_triple=universal-apple-darwin
                    prefix_name=macos-universal
                ;;
                *)
                    echo "Invalid macos architecture" >&2
                    exit 1
                ;;
            esac
            if [ "$arch" != "universal" ]; then
                sdk=$(xcrun --sdk macosx --show-sdk-path 2>/dev/null || true)
                export CC="clang"
                export CXX="clang++"
                if [ -n "$sdk" ] && [ -d "$sdk" ]; then
                    export CFLAGS="${CFLAGS:+$CFLAGS }-target $target_triple -isysroot $sdk"
                    export CXXFLAGS="${CXXFLAGS:+$CXXFLAGS }-target $target_triple -isysroot $sdk"
                    export LDFLAGS="${LDFLAGS:+$LDFLAGS }-target $target_triple -isysroot $sdk"
                else
                    export CFLAGS="${CFLAGS:+$CFLAGS }-target $target_triple"
                    export CXXFLAGS="${CXXFLAGS:+$CXXFLAGS }-target $target_triple"
                    export LDFLAGS="${LDFLAGS:+$LDFLAGS }-target $target_triple"
                fi
            fi
            export cross_system=darwin
        ;;
        ios)
            case "$1" in
                arm64)
                    export target_triple=arm64-apple-ios
                    sdk=$(xcrun --sdk iphoneos --show-sdk-path 2>/dev/null || true)
                    prefix_name=ios-arm64
                ;;
                x86_64)
                    export target_triple=x86_64-apple-ios-simulator
                    sdk=$(xcrun --sdk iphonesimulator --show-sdk-path 2>/dev/null || true)
                    prefix_name=ios-x86_64
                ;;
                *)
                    echo "Invalid ios architecture" >&2
                    exit 1
                ;;
            esac
            if [ "$1" = "x86_64" ]; then
                export CC="xcrun --sdk iphonesimulator clang -arch x86_64 -mios-simulator-version-min=13.0"
                export CXX="xcrun --sdk iphonesimulator clang++ -arch x86_64 -mios-simulator-version-min=13.0"
                export OBJC="xcrun --sdk iphonesimulator clang -arch x86_64 -mios-simulator-version-min=13.0"
                export OBJCXX="xcrun --sdk iphonesimulator clang++ -arch x86_64 -mios-simulator-version-min=13.0"
            else
                export CC="xcrun --sdk iphoneos clang -arch arm64 -miphoneos-version-min=13.0"
                export CXX="xcrun --sdk iphoneos clang++ -arch arm64 -miphoneos-version-min=13.0"
                export OBJC="xcrun --sdk iphoneos clang -arch arm64 -miphoneos-version-min=13.0"
                export OBJCXX="xcrun --sdk iphoneos clang++ -arch arm64 -miphoneos-version-min=13.0"
            fi
            export cross_system=darwin
            export CFLAGS="-fembed-bitcode"
            export CXXFLAGS="-fembed-bitcode"
        ;;
        linux)
            case "$1" in
                x86_64)
                    export target_triple=x86_64-linux-gnu
                    prefix_name=linux-x86_64
                ;;
                aarch64|arm64)
                    export target_triple=aarch64-linux-gnu
                    prefix_name=linux-aarch64
                ;;
                *)
                    echo "Invalid linux architecture" >&2
                    exit 1
                ;;
            esac
            if command -v zig >/dev/null 2>&1; then
                case "$target_triple" in
                    *-unknown-linux-gnu)
                        zig_target="${target_triple%%-unknown-*}-linux-gnu"
                    ;;
                    *-unknown-linux-musl)
                        zig_target="${target_triple%%-unknown-*}-linux-musl"
                    ;;
                    *)
                        zig_target="$target_triple"
                    ;;
                esac
                export ZIG=1
                export ZIG_TARGET="$zig_target"
                export CC="$PWD/tools/zig-cc.sh"
                export CXX="$PWD/tools/zig-cxx.sh"
                export AR="$PWD/tools/ar-wrap.sh"
            else
                export CC="clang"
                export CXX="clang++"
                export CFLAGS="${CFLAGS:+$CFLAGS }-target $target_triple"
                export CXXFLAGS="${CXXFLAGS:+$CXXFLAGS }-target $target_triple"
            fi
            if [ -n "$SYSROOT" ]; then
                export CFLAGS="${CFLAGS:+$CFLAGS }--sysroot $SYSROOT"
                export CXXFLAGS="${CXXFLAGS:+$CXXFLAGS }--sysroot $SYSROOT"
                export LDFLAGS="${LDFLAGS:+$LDFLAGS }--sysroot $SYSROOT"
            fi
            export cross_system=linux
        ;;
        windows)
            case "$1" in
                x86_64)
                    export target_triple=x86_64-w64-mingw32
                    prefix_name=windows-x86_64
                ;;
                aarch64|arm64)
                    export target_triple=aarch64-w64-mingw32
                    prefix_name=windows-aarch64
                ;;
                *)
                    echo "Invalid windows architecture" >&2
                    exit 1
                ;;
            esac
            if command -v ${target_triple}-gcc >/dev/null 2>&1; then
                export CC="${target_triple}-gcc"
                export CXX="${target_triple}-g++"
                export AR="${target_triple}-ar"
                export RANLIB="${target_triple}-ranlib"
                export NM="${target_triple}-nm"
                export STRIP="${target_triple}-strip"
                export WINDRES="${target_triple}-windres"
                export TARGET_TRIPLE="$target_triple"
            else
                export CC="clang -target $target_triple"
                export CXX="clang++ -target $target_triple"
                export WINDRES="llvm-windres"
                export TARGET_TRIPLE="$target_triple"
            fi
            export cross_system=windows
        ;;
        *)
            echo "Invalid platform '$platform'" >&2
            exit 1
        ;;
    esac
    export prefix_dir="$PWD/prefix/$prefix_name"
    if [ "$platform" = "windows" ]; then
        export PATH="$PWD/bin:$PATH"
    fi
}

setup_prefix () {
	if [ ! -d "$prefix_dir" ]; then
		mkdir -p "$prefix_dir"
		# enforce flat structure (/usr/local -> /)
		ln -s . "$prefix_dir/usr"
		ln -s . "$prefix_dir/local"
	fi

    local cpu_family=${target_triple%%-*}
	[ "$cpu_family" == "i686" ] && cpu_family=x86

	if ! command -v pkg-config >/dev/null; then
		echo "pkg-config not provided!"
		return 1
	fi
    export PKG_CONFIG_PATH="$prefix_dir/lib/pkgconfig${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}"

    if [ "$ZIG" = "1" ]; then
        c_bin="['zig','cc','-target','$ZIG_TARGET']"
        cpp_bin="['zig','c++','-target','$ZIG_TARGET']"
        ar_bin="['bash','$PWD/tools/ar-wrap.sh']"
    else
        # Convert shell commands into Meson arrays: ['prog','arg1','arg2',...]
        meson_arr_from_cmd () {
            local cmd="$1"
            local IFS=' '
            read -r -a parts <<< "$cmd"
            local out="["
            local first=1
            for p in "${parts[@]}"; do
                # escape single quotes
                p=${p//\'/\'\\\'\'}
                if [ $first -eq 1 ]; then
                    out="$out'$p'"
                    first=0
                else
                    out="$out,'$p'"
                fi
            done
            out="$out]"
            printf "%s" "$out"
        }
        c_bin="$(meson_arr_from_cmd "$CC")"
        cpp_bin="$(meson_arr_from_cmd "$CXX")"
        ar_bin="$(meson_arr_from_cmd "$AR")"
    fi
    if [ "$platform" = "ios" ]; then
        objc_bin="$c_bin"
        objcpp_bin="$cpp_bin"
    else
        objc_bin="'clang'"
        objcpp_bin="'clang++'"
    fi

	# meson wants to be spoonfed this file, so create it ahead of time
	# also define: release build, static libs and no source downloads at runtime(!!!)
    cat >"$prefix_dir/crossfile.tmp" <<CROSSFILE
[built-in options]
buildtype = 'release'
default_library = 'static'
wrap_mode = 'nodownload'
prefix = '/usr/local'
[binaries]
c = $c_bin
cpp = $cpp_bin
objc = $objc_bin
objcpp = $objcpp_bin
ar = $ar_bin
nm = '$NM'
strip = '$STRIP'
windres = '$WINDRES'
pkgconfig = 'pkg-config'
pkg-config = 'pkg-config'
[host_machine]
system = '$cross_system'
cpu_family = '$cpu_family'
cpu = '$cpu_family'
endian = 'little'
[target_machine]
system = '$cross_system'
cpu_family = '$cpu_family'
cpu = '$cpu_family'
endian = 'little'
CROSSFILE
	# also avoid rewriting it needlessly
	if cmp -s "$prefix_dir"/crossfile.{tmp,txt}; then
		rm "$prefix_dir/crossfile.tmp"
	else
		mv "$prefix_dir"/crossfile.{tmp,txt}
	fi
}

copy_to_resources () {
    case "$platform" in
        macos|linux|windows)
            local os_id
            case "$platform" in
                macos) os_id=darwin ;;
                linux) os_id=linux ;;
                windows) os_id=windows ;;
            esac
            local res_base="$PWD/../mpv/src/jvmMain/resources"
            mkdir -p "$res_base"
            if [ "$platform" = "macos" ] && [ "$arch" = "universal" ]; then
                local src="$PWD/prefix/macos-universal/lib"
                [ -d "$src" ] || return 0
                for lib in "$src"/*.dylib "$src"/*.so "$src"/*.dll; do
                    [ -e "$lib" ] || continue
                    for a in aarch64 x86_64; do
                        local dst1="$res_base/$os_id-$a"
                        mkdir -p "$dst1"
                        cp -f "$lib" "$dst1/$(basename "$lib")"
                    done
                done
            else
                local arch_id
                case "$arch" in
                    arm64|aarch64) arch_id=aarch64 ;;
                    x86_64) arch_id=x86_64 ;;
                    x86) arch_id=x86 ;;
                    *) arch_id="$arch" ;;
                esac
                local src="$prefix_dir/lib"
                [ -d "$src" ] || src="$prefix_dir/bin"
                [ -d "$src" ] || return 0
                for lib in "$src"/*.dylib "$src"/*.so "$src"/*.dll; do
                    [ -e "$lib" ] || continue
                    local dst1="$res_base/$os_id-$arch_id"
                    mkdir -p "$dst1"
                    cp -f "$lib" "$dst1/$(basename "$lib")"
                done
            fi
        ;;
    esac
}

build () {
    local t="$1"
    [ "$t" = "ass" ] && t="libass"
	if [ $t != "mpv-android" ] && [ ! -d deps/$t ]; then
		printf >&2 '\e[1;31m%s\e[m\n' "Target $t not found"
		return 1
	fi
    # macOS universal: build both arches and merge
    if [ "$platform" == "macos" ] && [ "$arch" == "universal" ]; then
        printf >&2 '\e[1;34m%s\e[m\n' "Building universal for $1..."
        # Build arm64
        ( arch=arm64 platform=macos target="$1" clang=$clang cleanbuild=$cleanbuild nodeps=$nodeps \
          bash "$0" --arch arm64 "$1" )
        # Build x86_64
        ( arch=x86_64 platform=macos target="$1" clang=$clang cleanbuild=$cleanbuild nodeps=$nodeps \
          bash "$0" --arch x86_64 "$1" )
        # Merge into macos-universal prefix
        local pref_arm="$PWD/prefix/macos-arm64"
        local pref_x64="$PWD/prefix/macos-x86_64"
        local pref_uni="$PWD/prefix/macos-universal"
        mkdir -p "$pref_uni"/{lib,include}
        rsync -a --delete "$pref_arm/include/" "$pref_uni/include/"
        # create fat libs for *.a and *.dylib present in both
        for lib in $(cd "$pref_arm/lib" && ls *.a *.dylib 2>/dev/null || true); do
            [ -f "$pref_x64/lib/$lib" ] || continue
            lipo -create "$pref_arm/lib/$lib" "$pref_x64/lib/$lib" -output "$pref_uni/lib/$lib"
        done
        return 0
    fi
	if [ $nodeps -eq 0 ]; then
		printf >&2 '\e[1;34m%s\e[m\n' "Preparing $1..."
		local deps=$(getdeps $t)
        if [ "$platform" = "ios" ]; then
            local filtered=
            for dep in $deps; do
                [ "$dep" = "lua" ] && continue
                filtered="$filtered $dep"
            done
            deps="$filtered"
        fi
		echo >&2 "Dependencies: $deps"
		for dep in $deps; do
			build $dep
		done
	fi
	printf >&2 '\e[1;34m%s\e[m\n' "Building $1..."
	if [ "$t" == "mpv-android" ]; then
		pushd ..
		BUILDSCRIPT=buildscripts/scripts/$t.sh
	else
		pushd deps/$t
		BUILDSCRIPT=../../scripts/$t.sh
	fi
	[ $cleanbuild -eq 1 ] && $BUILDSCRIPT clean
	$BUILDSCRIPT build
	popd
}

usage () {
	printf '%s\n' \
		"Usage: buildall.sh [options] [target]" \
		"Builds the specified target (default: $target)" \
		"-n             Do not build dependencies" \
		"--clean        Clean build dirs before compiling" \
        "--gcc          Use gcc compiler (unsupported!)" \
        "--platform <p> Target platform (default: android; supported: android, macos, ios, linux, windows)" \
        "--arch <arch>  Target arch (android: armv7l,arm64,x86,x86_64; macos: x86_64,arm64,universal; ios: arm64,x86_64; linux: x86_64,aarch64; windows: x86_64,aarch64)" \
        "--sysroot <d>  Sysroot path used when cross-compiling linux"
	exit 0
}

while [ $# -gt 0 ]; do
	case "$1" in
		--clean)
		cleanbuild=1
		;;
		-n|--no-deps)
		nodeps=1
		;;
		--gcc)
		clang=0
		;;
        --platform)
        shift
        platform=$1
        ;;
		--arch)
		shift
		arch=$1
		;;
        --sysroot)
        shift
        SYSROOT=$1
        export SYSROOT
        ;;
		-h|--help)
		usage
		;;
		-*)
		echo "Unknown flag $1" >&2
		exit 1
		;;
		*)
		target=$1
		;;
	esac
	shift
done

load_target $arch
setup_prefix
build $target
copy_to_resources

[ "$target" == "mpv-android" ] && \
	ls -lh ../app/build/outputs/apk/{default,api29}/*/*.apk

exit 0
