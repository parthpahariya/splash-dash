#!/usr/bin/env bash
# Build the website image, check its pages, and validate the host nginx configs.
# Works with a host Docker daemon and Docker-in-Docker: no host ports or bind mounts.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

image=the-last-sail-web:check
domain=thelastsail.boringman.in
cert_dir=$(mktemp -d)
container=
cleanup() {
    if [[ -n "$container" ]]; then docker rm -f "$container" >/dev/null 2>&1 || true; fi
    rm -rf -- "$cert_dir"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

docker build -f docker/web.Dockerfile -t "$image" .

container=$(docker run -d "$image")
for _ in $(seq 30); do
    status=$(docker inspect --format '{{.State.Health.Status}}' "$container")
    [[ "$status" == healthy ]] && break
    sleep 1
done
[[ "$status" == healthy ]] || { docker logs "$container"; echo "Web container is $status." >&2; exit 1; }

fetch() { docker exec "$container" wget -q -O - "http://127.0.0.1$1"; }
expect() {
    local body
    body=$(fetch "$1")
    grep -qF -- "$2" <<<"$body" || { echo "Expected $1 to contain: $2" >&2; exit 1; }
}
expect / '<title>The Last Sail</title>'
expect /privacy/ 'Privacy Policy'
expect /assets/play-store-qr.svg '<svg'
# The stylesheet URL must carry the content hash stamped at build time (see docker/web.Dockerfile).
version=$(docker exec "$container" md5sum /usr/share/nginx/html/site.css | cut -c1-10)
expect / "site.css?v=$version"
expect /privacy/ "site.css?v=$version"
# Browsers must revalidate, so a deploy never mixes new pages with a stale cached stylesheet.
for path in / /site.css /assets/gameplay.jpg; do
    headers=$(docker exec "$container" wget -S --spider "http://127.0.0.1$path" 2>&1)
    grep -qi '^  Cache-Control: no-cache$' <<<"$headers" || { echo "$headers" >&2; echo "Expected no-cache on $path." >&2; exit 1; }
done
# /privacy must redirect relatively so the public HTTPS origin is kept behind the proxy.
headers=$(docker exec "$container" wget -S --spider http://127.0.0.1/privacy 2>&1)
grep -qx '  Location: /privacy/' <<<"$headers" || { echo "$headers" >&2; echo 'Expected a relative redirect.' >&2; exit 1; }
if fetch /missing >/dev/null 2>&1; then
    echo 'Expected /missing to return 404.' >&2
    exit 1
fi
docker rm -f "$container" >/dev/null
container=

# Validate the host configs against a throwaway certificate.
mkdir -p "$cert_dir/live/$domain"
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj "/CN=$domain" \
    -keyout "$cert_dir/live/$domain/privkey.pem" \
    -out "$cert_dir/live/$domain/fullchain.pem" 2>/dev/null
for config in deploy/nginx-http.conf deploy/nginx.conf; do
    container=$(docker create nginx:1.28-alpine nginx -t)
    docker cp "$config" "$container:/etc/nginx/conf.d/default.conf"
    docker cp "$cert_dir" "$container:/etc/letsencrypt"
    docker start --attach "$container"
    test "$(docker inspect --format '{{.State.ExitCode}}' "$container")" = 0
    docker rm "$container" >/dev/null
    container=
done
echo 'Website image and nginx configuration OK.'
