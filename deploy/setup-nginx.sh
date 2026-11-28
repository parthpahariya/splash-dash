#!/usr/bin/env bash
# Run as root after Compose reports the web container healthy.
set -euo pipefail

project_dir=${1:?Pass the project directory}
nginx_dir=/etc/nginx
certbot_dir=/etc/letsencrypt
webroot=/var/www/letsencrypt
domain=thelastsail.boringman.in
# Wait for another deployment (for example Meal Manager on the same VM) to release apt.
apt_options=(-o DPkg::Lock::Timeout=600)

if ! command -v nginx >/dev/null || ! nginx -v >/dev/null 2>&1; then
    apt-get "${apt_options[@]}" update
    DEBIAN_FRONTEND=noninteractive apt-get "${apt_options[@]}" install -y nginx
fi
if ! command -v certbot >/dev/null || ! certbot --version >/dev/null 2>&1; then
    apt-get "${apt_options[@]}" update
    DEBIAN_FRONTEND=noninteractive apt-get "${apt_options[@]}" install -y certbot
fi
install -m 755 -d "$nginx_dir/conf.d" "$webroot"

config=nginx-http.conf
if [[ -s "$certbot_dir/live/$domain/fullchain.pem" && -s "$certbot_dir/live/$domain/privkey.pem" ]]; then
    config=nginx.conf
fi
install -m 644 "$project_dir/deploy/$config" "$nginx_dir/conf.d/the-last-sail.conf"
# The stock site claims default_server on port 80; other sites' configs are left alone.
rm -f "$nginx_dir/sites-enabled/default"

nginx -t
systemctl enable --now nginx
systemctl reload nginx

# Webroot validation leaves Nginx configuration under deployment control.
install -m 755 -d "$certbot_dir/renewal-hooks/deploy"
printf '%s\n' '#!/usr/bin/env bash' 'set -euo pipefail' \
    'nginx -t' 'systemctl reload nginx' \
    > "$certbot_dir/renewal-hooks/deploy/reload-nginx"
chmod 755 "$certbot_dir/renewal-hooks/deploy/reload-nginx"
certbot certonly --webroot --webroot-path "$webroot" \
    --cert-name "$domain" --domain "$domain" \
    --non-interactive --agree-tos --register-unsafely-without-email --keep-until-expiring

install -m 644 "$project_dir/deploy/nginx.conf" "$nginx_dir/conf.d/the-last-sail.conf"
nginx -t
systemctl reload nginx
systemctl enable --now certbot.timer

for path in web-health privacy/; do
    curl --fail --silent --show-error --retry 5 --retry-connrefused --retry-delay 1 \
        --resolve "$domain:443:127.0.0.1" "https://$domain/$path" -o /dev/null
done
echo "Nginx proxy healthy: https://$domain -> 127.0.0.1:4174"
