#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 3 || ! "$1" =~ ^[0-9a-f]{40}$ || ! "$2" =~ ^linux/(amd64|arm64)$ ]]; then
    echo "Usage: bash scripts/package-offline.sh <commit-sha> <linux/amd64|linux/arm64> <output-directory>" >&2
    exit 2
fi

commit=$1
platform=$2
architecture=${platform#linux/}
output=$3
mkdir -p -- "$output"
output=$(cd -- "$output" && pwd)
cd -- "$(dirname -- "$0")/.."

staging=$(mktemp -d "$output/.offline.XXXXXXXX")
trap 'rm -rf -- "$staging"' EXIT
# Use a distinct tag for each build, including retries of the same commit.
tag="$commit-$architecture-${staging##*.}"
name="sllapp-offline-$tag.zip"
package="$staging/package"
mkdir -p "$package/offline" "$package/model/artifacts"

(
    cd model/artifacts
    shasum -a 256 -c model.joblib.sha256
)
cp compose.offline.yaml "$package/"
cp scripts/offline/*.bat scripts/offline/Obsluga-offline.ps1 "$package/"
cp docs/offline-quick-start.md "$package/README.md"
cp LICENSE "$package/"
cp model/artifacts/model.joblib{,.sha256} "$package/model/artifacts/"
cp backend/src/main/resources/application.yml "$package/offline/application.yml"
cp "$package/offline/application.yml" "$package/offline/application.default.yml"

metadata="$package/offline/images.env"
cat > "$metadata" <<META
SLLAPP_IMAGE_TAG=$tag
SLLAPP_PLATFORM=$platform
# Source commit: $commit
META
images=()
for service in frontend backend model; do
    image="sllapp/$service:$tag"
    docker buildx build --platform "$platform" --load --tag "$image" "$service"
    actual=$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "$image")
    if [[ "$actual" != "$platform" ]]; then
        echo "Expected $platform for $image, found $actual" >&2
        exit 1
    fi
    images+=("$image")
    printf '# Image: %s\n' "$image" >> "$metadata"
done

archive="$package/offline/images.tar.gz"
docker image save "${images[@]}" | gzip -1 > "$archive"
checksum=$(shasum -a 256 "$archive")
bytes=$(wc -c < "$archive")
printf '# images.tar.gz SHA-256: %s\n# images.tar.gz bytes: %d\n' "${checksum%% *}" "$bytes" >> "$metadata"

(
    cd "$package"
    SLLAPP_IMAGE_TAG="$tag" SLLAPP_PLATFORM="$platform" \
        docker compose --env-file offline/images.env -f compose.offline.yaml config --quiet
    zip -q -0 -r "$staging/$name" .
)
unzip -tq "$staging/$name"
mv -- "$staging/$name" "$output/$name"
shasum -a 256 "$output/$name"
wc -c "$output/$name"
