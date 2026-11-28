#!/usr/bin/env bash
# Sent over SSH by CI; requires a Debian/Ubuntu VM with SSH and sudo.
# Safe to run on the same VM as Meal Manager: separate checkout, lock, port and nginx site.
set -euo pipefail
umask 077

repository=${1:?Pass the repository namespace/project}
branch=${2:?Pass the deployment branch}
revision=${3:?Pass the tested commit SHA}
project_dir="$HOME/the-last-sail"
repository_server=${DEPLOY_REPO_SERVER:?Pass the repository server URL}
repository_username=${DEPLOY_REPO_USERNAME:?Pass the repository token username}

if ! [[ "$repository" =~ ^[a-zA-Z0-9_.-]+(/[a-zA-Z0-9_.-]+)+$ &&
        "$repository_server" =~ ^https://[a-zA-Z0-9][a-zA-Z0-9.-]*(:[0-9]+)?(/[a-zA-Z0-9_.-]+)*$ &&
        "$repository_username" =~ ^[a-zA-Z0-9_-]+$ &&
        "$revision" =~ ^[0-9a-f]{40}$ ]]; then
    echo 'Invalid repository connection settings or commit SHA.' >&2
    exit 1
fi
test -n "${DEPLOY_REPO_TOKEN:-}" || {
    echo 'The workflow must supply a repository token.' >&2
    exit 1
}

source /etc/os-release
case "$ID" in
    debian)
        docker_distribution=debian
        docker_codename=$VERSION_CODENAME
        ;;
    ubuntu)
        docker_distribution=ubuntu
        docker_codename=${UBUNTU_CODENAME:-$VERSION_CODENAME}
        ;;
    *)
        echo 'Automatic VM setup supports Debian and Ubuntu only.' >&2
        exit 1
        ;;
esac
docker_repository="https://download.docker.com/linux/$docker_distribution"

root_command=()
if [[ $(id -u) != 0 ]]; then
    root_command=(sudo -n)
fi
"${root_command[@]}" true
# Wait for another deployment (for example Meal Manager) to release apt.
apt_options=(-o DPkg::Lock::Timeout=600)

bundle_dir=$(mktemp -d)
trap 'rm -rf -- "$bundle_dir"' EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
printf '%s' "$DEPLOY_REPO_TOKEN" > "$bundle_dir/repo-token"
unset DEPLOY_REPO_TOKEN

# Debian and Ubuntu include flock in util-linux. Lock bootstrap as well
# as source updates and container replacement, including on the first run.
exec 9>"$HOME/.the-last-sail-deploy.lock"
flock -w 1200 9

if ! command -v git >/dev/null || ! command -v curl >/dev/null || ! command -v make >/dev/null; then
    "${root_command[@]}" apt-get "${apt_options[@]}" update
    "${root_command[@]}" env DEBIAN_FRONTEND=noninteractive apt-get "${apt_options[@]}" install -y \
        ca-certificates curl git make util-linux
fi

if ! command -v docker >/dev/null || ! docker compose version >/dev/null 2>&1; then
    # Install from Docker's official apt repository; do not pipe a downloaded
    # convenience script into a root shell.
    "${root_command[@]}" apt-get "${apt_options[@]}" update
    "${root_command[@]}" env DEBIAN_FRONTEND=noninteractive apt-get "${apt_options[@]}" install -y \
        ca-certificates curl
    "${root_command[@]}" install -m 755 -d /etc/apt/keyrings
    curl -fsSL "$docker_repository/gpg" -o "$bundle_dir/docker.asc"
    "${root_command[@]}" install -m 644 "$bundle_dir/docker.asc" /etc/apt/keyrings/docker.asc
    printf '%s\n' \
        'Types: deb' \
        "URIs: $docker_repository" \
        "Suites: $docker_codename" \
        'Components: stable' \
        "Architectures: $(dpkg --print-architecture)" \
        'Signed-By: /etc/apt/keyrings/docker.asc' \
        | "${root_command[@]}" tee /etc/apt/sources.list.d/docker.sources >/dev/null
    "${root_command[@]}" apt-get "${apt_options[@]}" update
    "${root_command[@]}" env DEBIAN_FRONTEND=noninteractive apt-get "${apt_options[@]}" install -y \
        docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi
"${root_command[@]}" systemctl enable --now docker

# The job-scoped token is used only by Git and never stored in the remote URL
# or credential helpers. No separate repository deploy key is required on the VM.
cat > "$bundle_dir/git-askpass" <<'ASKPASS'
#!/usr/bin/env bash
case "$1" in
    *Username*) printf '%s\n' "$LAST_SAIL_GIT_USERNAME" ;;
    *Password*) cat "$LAST_SAIL_TOKEN_FILE" ;;
    *) exit 1 ;;
esac
ASKPASS
chmod 700 "$bundle_dir/git-askpass"
export GIT_ASKPASS="$bundle_dir/git-askpass"
export GIT_TERMINAL_PROMPT=0
export LAST_SAIL_TOKEN_FILE="$bundle_dir/repo-token"
export LAST_SAIL_GIT_USERNAME="$repository_username"
repository_url="$repository_server/$repository.git"

git check-ref-format --branch "$branch" >/dev/null
if [[ ! -d "$project_dir/.git" ]]; then
    git -c credential.helper= clone --branch "$branch" -- "$repository_url" "$project_dir"
fi
cd "$project_dir"
git diff --quiet && git diff --cached --quiet || {
    echo 'The VM checkout has local changes. Resolve them before deploying.' >&2
    exit 1
}
git remote set-url origin "$repository_url"
git -c credential.helper= fetch origin "$branch"
git switch "$branch"
git merge --ff-only FETCH_HEAD
[[ $(git rev-parse HEAD) == "$revision" ]] || {
    echo 'The branch has moved since this run started. Deploy the latest workflow run.' >&2
    exit 1
}
unset GIT_ASKPASS LAST_SAIL_TOKEN_FILE LAST_SAIL_GIT_USERNAME
rm -f "$bundle_dir/git-askpass" "$bundle_dir/repo-token"

if docker info >/dev/null 2>&1; then
    docker_command=(docker)
elif sudo -n docker info >/dev/null 2>&1; then
    docker_command=(sudo -n docker)
else
    echo 'Cannot access Docker directly or through passwordless sudo.' >&2
    exit 1
fi
make_command=(make "DOCKER=${docker_command[*]}")
"${make_command[@]}" deploy
"${root_command[@]}" bash "$project_dir/deploy/setup-nginx.sh" "$project_dir"
"${make_command[@]}" deploy-status
echo "Deployment healthy: $(git rev-parse --short HEAD)"
