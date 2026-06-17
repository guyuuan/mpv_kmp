#!/bin/bash -e
buildscripts/buildall.sh --platform android --arch arm64
buildscripts/buildall.sh --platform android --arch x86_64
buildscripts/buildall.sh --platform macos  --arch x86_64
buildscripts/buildall.sh --platform macos  --arch arm64
buildscripts/buildall.sh --platform linux  --arch x86_64
buildscripts/buildall.sh --platform linux  --arch arm64
buildscripts/buildall.sh --platform windows --arch x86_64
