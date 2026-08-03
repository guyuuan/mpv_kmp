#!/bin/bash -e

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$script_dir"

apply_mpv_patches() {
	local mpv_dir="$script_dir/deps/mpv"
	local patch_dir="$script_dir/patches/mpv"
	local patch_file patch_name

	for patch_file in "$patch_dir"/*.patch; do
		[ -e "$patch_file" ] || continue
		patch_name="$(basename "$patch_file")"

		if git -C "$mpv_dir" apply --reverse --check "$patch_file" >/dev/null 2>&1; then
			echo "mpv patch is already applied: $patch_name"
			continue
		fi

		if ! git -C "$mpv_dir" apply --check "$patch_file"; then
			echo "Failed to apply mpv patch: $patch_file" >&2
			return 1
		fi

		git -C "$mpv_dir" apply "$patch_file"
		echo "Applied mpv patch: $patch_name"
	done
}

./include/download-sdk.sh
./include/download-deps.sh
apply_mpv_patches
