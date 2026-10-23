#!/usr/bin/env bash
set -euo pipefail
version=$(python3 -c 'import json; print(json.load(open("signing/version.json"))["version_name"])')
tag="v${version}"
mkdir -p dist/publish
cp "dist/signed/TCL-Light-${version}.apk" dist/publish/
cp "dist/signed/TCL-Light-${version}.apk" dist/publish/TCL-Light-beta.apk
cp signing/tcl-light-release.pem dist/publish/TCL-Light-certificado.pem
cp dist/upgrade-qa/TCL-Light-0.7.0-verificacao.json dist/publish/
if ! gh release view "$tag" > /dev/null 2>&1; then
  gh release create "$tag" --title "TCL Light ${version}" --prerelease --notes-file RELEASE_NOTES.md
fi
gh release upload "$tag" "dist/publish/TCL-Light-${version}.apk" dist/publish/TCL-Light-certificado.pem dist/publish/TCL-Light-0.7.0-verificacao.json --clobber
if ! gh release view beta > /dev/null 2>&1; then
  gh release create beta --title "TCL Light ${version}" --prerelease --notes-file RELEASE_NOTES.md
fi
gh release upload beta dist/publish/* --clobber
gh release edit beta --title "TCL Light ${version}" --notes-file RELEASE_NOTES.md
