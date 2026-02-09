#!/bin/bash -e

. ../../include/path.sh

if [ "$1" == "build" ]; then
	true
elif [ "$1" == "clean" ]; then
	make clean
	exit 0
else
	exit 255
fi

# Building separately from source tree is not supported, this means we are forced to always clean
$0 clean

mycflags=( -fPIC )
if [ "$platform" == "android" ]; then
    mycflags+=(
        -Dgetlocaledecpoint\\\(\\\)=\\\(46\\\)
        -Dlua_fseek
    )
fi

# LUA_T= and LUAC_T= to disable building lua & luac
# -Dgetlocaledecpoint()=('.') fixes bionic missing decimal_point in localeconv
plat=posix
[ "$platform" == "android" ] && plat=linux
[ "$platform" == "linux" ] && plat=linux
[ "$platform" == "macos" ] && plat=macosx
[ "$platform" == "windows" ] && plat=mingw
make CC="$CC" AR="$AR rc" RANLIB="$RANLIB" \
    MYCFLAGS="${mycflags[*]}" \
    PLAT=$plat LUA_T= LUAC_T= -j$cores

# TO_BIN=/dev/null disables installing lua & luac
make INSTALL=${INSTALL:-install} INSTALL_TOP="$prefix_dir" TO_BIN=/dev/null install

# make pc only generates a partial pkg-config file because ????
mkdir -p $prefix_dir/lib/pkgconfig
make pc >$prefix_dir/lib/pkgconfig/lua.pc
cat >>$prefix_dir/lib/pkgconfig/lua.pc <<'EOF'
Name: Lua
Description:
Version: ${version}
Libs: -L${libdir} -llua
Cflags: -I${includedir}
EOF
