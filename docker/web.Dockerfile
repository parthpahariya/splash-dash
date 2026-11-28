# Static website for thelastsail.boringman.in. Build from the repository root:
#   docker build -f docker/web.Dockerfile .
FROM nginx:1.28-alpine

COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY web/ /usr/share/nginx/html/

# Pages link the stylesheet as site.css?v=dev. Replace "dev" with a hash of the file so
# every stylesheet change gets a new URL and no browser pairs new pages with an old cached copy.
RUN cd /usr/share/nginx/html \
    && version=$(md5sum site.css | cut -c1-10) \
    && find . -name '*.html' -exec sed -i "s/site\.css?v=dev/site.css?v=$version/g" {} + \
    && ! grep -rl 'site\.css?v=dev' --include='*.html' .

EXPOSE 80
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1/web-health || exit 1
