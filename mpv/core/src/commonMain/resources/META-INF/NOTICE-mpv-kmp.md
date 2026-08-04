# mpv-kmp native library notice

The Kotlin and Gradle plugin code in this distribution is licensed under the
Apache License 2.0. A copy is provided as `LICENSE-mpv-kmp.txt`.

Some published artifacts also contain dynamically linked native libraries,
including libmpv and FFmpeg. These libraries are built with mpv's GPL features
disabled (`-Dgpl=false`) and FFmpeg's GPL features disabled
(`--disable-gpl --enable-version3`). They remain subject to their own upstream
copyright notices and license terms, including the GNU Lesser General Public
License versions included in the `licenses` directory.

The corresponding dependency versions and build scripts are maintained in the
mpv-kmp source repository:

https://github.com/guyuuan/mpv_kmp

Use the source tag matching the published artifact version when rebuilding or
replacing the bundled native libraries. The upstream projects remain the
authoritative source for their complete copyright and licensing notices.
