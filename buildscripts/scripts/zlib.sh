#!/bin/bash -e

. ../../include/path.sh

build=_build${build_suffix}

if [ "$1" == "build" ]; then
    true
elif [ "$1" == "clean" ]; then
    rm -rf "$build"
    exit 0
else
    exit 255
fi

mkdir -p "$build"
cd "$build"
srcdir=".."
[ -f "$srcdir/configure" ] || { echo "Missing zlib source in deps/zlib; run include/download-deps.sh" >&2; exit 255; }

arch_family=${target_triple%%-*}
cc_bin="$CC"
target_flags=
if [ "$ZIG" = "1" ]; then
    target_flags="-target $ZIG_TARGET"
fi

case "$platform" in
    linux|macos|ios|android)
        CC="$cc_bin" AR="$AR" RANLIB="$RANLIB" CFLAGS="$CFLAGS $target_flags" "$srcdir/configure" --prefix=/usr/local --static
    ;;
    windows)
        CC="$cc_bin" AR="$AR" RANLIB="$RANLIB" CFLAGS="$CFLAGS" "$srcdir/configure" --prefix=/usr/local --static
    ;;
esac
rm -f libz.a
make AR="$AR" ARFLAGS="rcs" RANLIB="$RANLIB" -j"$cores" libz.a
mkdir -p "$prefix_dir/lib" "$prefix_dir/include"
cp -f libz.a "$prefix_dir/lib/"
cp -f "$srcdir/zlib.h" "$srcdir/zconf.h" "$prefix_dir/include/"

pcdir="$prefix_dir/lib/pkgconfig"
mkdir -p "$pcdir"
version=$(grep -E '^#define[[:space:]]+ZLIB_VERSION' "$srcdir/zlib.h" 2>/dev/null | awk '{print $3}' | tr -d '"')
[ -z "$version" ] && version="unknown"
suff="$pcdir/zlib.pc"
cat >"$suff" <<EOF
prefix=/usr/local
exec_prefix=\${prefix}
libdir=\${exec_prefix}/lib
includedir=\${prefix}/include

Name: zlib
Description: zlib compression library
Version: $version
Libs: -L$prefix_dir/lib -lz
Cflags: -I$prefix_dir/include
EOF
