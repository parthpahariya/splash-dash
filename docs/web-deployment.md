# Website: thelastsail.boringman.in

The static website in [`web/`](../web) (home page with the Google Play link and QR code, and the
privacy policy at `/privacy/`) runs in an nginx Docker container on a Google Cloud VM. Host nginx
on the VM terminates HTTPS with a Let's Encrypt certificate and proxies to the container. This is
the same setup as Meal Manager, and both can share one VM.

```
browser ──https──▶ host nginx :443 (thelastsail.boringman.in) ──▶ 127.0.0.1:4174 ──▶ web container :80
```

| File | Purpose |
| --- | --- |
| `web/` | The website files. Edit these to change the site. |
| `docker/web.Dockerfile`, `docker/nginx.conf` | Container image and its nginx config (security headers, caching, 404 page, `/web-health`). |
| `compose.yaml`, `compose.production.yaml` | Local run on port 8080; production binds `127.0.0.1:4174`. |
| `deploy/nginx.conf`, `deploy/nginx-http.conf` | Host nginx site, installed as `/etc/nginx/conf.d/the-last-sail.conf`. |
| `deploy/setup-nginx.sh` | Installs nginx and Certbot, gets the certificate, installs the HTTPS site. |
| `deploy/bootstrap-vm.sh`, `deploy/ci-deploy.sh` | CI sends these over SSH to install Docker, update `~/the-last-sail` and run `make deploy`. |
| `ci/check-web.sh` | Builds the image, checks the pages and validates the host nginx configs. |
| `.github/workflows/web.yml`, `.gitlab/web.yml` | Check on pull requests; deploy on pushes to `main`. |

## Run locally

```sh
make web         # starts Docker (Colima) if needed, serves http://127.0.0.1:8080/ and opens it
make web-up      # the same without opening the browser (WEB_PORT=9000 to change the port)
make web-check   # the same checks CI runs
make web-down
```

## Sharing a VM with Meal Manager

The website is set up to sit next to Meal Manager without touching it:

- Its own checkout (`~/the-last-sail`), Compose project (`the-last-sail`), deploy lock and port
  (`4174`; Meal Manager uses `4173`).
- Its own host nginx file, `the-last-sail.conf`, matched by `server_name`. It does not use
  `default_server`, which Meal Manager's site keeps.
- Its own certificate, `/etc/letsencrypt/live/thelastsail.boringman.in/`. The shared
  `certbot.timer` renews both.
- `apt-get` waits up to 10 minutes for the package lock if both deploy at once.

A separate VM works too, with the same steps.

## One-time setup

1. **DNS:** add an `A` record for `thelastsail.boringman.in` pointing at the VM's static external
   IPv4 address. Remove any `AAAA` record unless the VM serves IPv6. Wait until
   `dig +short thelastsail.boringman.in` returns the VM address; Certbot fails until it does.
2. **Firewall:** the VM must accept TCP 80 and 443 (already open if Meal Manager runs there) and
   TCP 22 from the CI runner. Keep port 4174 closed.
3. **SSH access for CI:** reuse Meal Manager's deploy key and VM user, or create a new key as
   described in Meal Manager's `docs/google-cloud-deployment.md` (section 2). The VM user needs
   passwordless sudo.
4. **GitHub:** in **Settings → Environments**, create an environment named `website` and add:

   | Type | Name | Value |
   | --- | --- | --- |
   | Variable | `GCP_VM_HOST` | VM external IP or hostname |
   | Variable | `GCP_VM_USER` | Linux SSH user with passwordless sudo |
   | Secret | `GCP_SSH_PRIVATE_KEY` | Private deploy key |
   | Secret | `GCP_SSH_KNOWN_HOSTS` | `<GCP_VM_HOST> ssh-ed25519 AAAA…` from `/etc/ssh/ssh_host_ed25519_key.pub` |

   Limit the environment's deployment branches to `main`.
   For GitLab, add the same names as CI/CD variables (the SSH key and known hosts as **File**
   variables) and protect `main`.

## Deploy

Merge website changes into `main`. The **Website** workflow runs `ci/check-web.sh`, then
`deploy/ci-deploy.sh` connects to the VM and:

1. installs Git, Make and Docker if missing, and clones or fast-forwards `~/the-last-sail` to
   the tested commit;
2. runs `make deploy`, which builds the image and waits for the container to be healthy on
   `127.0.0.1:4174`;
3. runs `deploy/setup-nginx.sh`, which installs the site config, gets or renews the
   certificate and checks `https://thelastsail.boringman.in/web-health` and `/privacy/`.

You can also start it with **Actions → Website → Run workflow** on `main`. To deploy from another
branch, change `DEPLOY_BRANCH`, the push branch and the deploy job's `if` in
`.github/workflows/web.yml` (and `DEPLOY_BRANCH` in `.gitlab/.gitlab-ci.yml`).

On the VM, check the container with `cd ~/the-last-sail && make deploy-status DOCKER='sudo -n docker'`.

## Privacy policy URL

Use `https://thelastsail.boringman.in/privacy/` as the privacy policy URL in Play Console and
App Store Connect. The old GitHub Pages copy only exists while Pages still publishes `docs/` from
the old release branch.
