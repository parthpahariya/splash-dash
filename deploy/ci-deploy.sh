#!/usr/bin/env bash
# Normalize provider inputs, then use one SSH/deployment path for GitHub and GitLab.
set -euo pipefail
umask 077
cd "$(dirname "${BASH_SOURCE[0]}")/.."

case "${1:-}" in
    github)
        branch=${DEPLOY_BRANCH:?Set the GitHub deployment branch}
        if ! [[ "${GITHUB_REF:-}" == "refs/heads/$branch" &&
                ( "${GITHUB_EVENT_NAME:-}" == push || "${GITHUB_EVENT_NAME:-}" == workflow_dispatch ) ]]; then
            echo 'Deploy only a push or manual workflow on the GitHub deployment branch.' >&2
            exit 1
        fi
        repository=${GITHUB_REPOSITORY:-}
        server=${GITHUB_SERVER_URL:-}
        revision=${GITHUB_SHA:-}
        token=${GITHUB_TOKEN:-}
        username=x-access-token
        credential_mode=contents
        ;;
    gitlab)
        deploy_branch=${DEPLOY_BRANCH:?Set the GitLab deployment branch}
        if ! [[ "${CI_COMMIT_BRANCH:-}" == "$deploy_branch" &&
                "${CI_COMMIT_REF_PROTECTED:-}" == true &&
                ( "${CI_PIPELINE_SOURCE:-}" == push || "${CI_PIPELINE_SOURCE:-}" == web ) ]]; then
            echo 'Deploy only a push or web pipeline on the protected deployment branch.' >&2
            exit 1
        fi
        branch=$CI_COMMIT_BRANCH
        repository=${CI_PROJECT_PATH:-}
        server=${CI_SERVER_URL:-}
        revision=${CI_COMMIT_SHA:-}
        token=${CI_JOB_TOKEN:-}
        username=gitlab-ci-token
        credential_mode=file
        ;;
    *)
        echo 'Usage: bash deploy/ci-deploy.sh {github|gitlab}' >&2
        exit 2
        ;;
esac

if ! [[ "$branch" =~ ^[a-zA-Z0-9_./-]+$ &&
        "$repository" =~ ^[a-zA-Z0-9_.-]+(/[a-zA-Z0-9_.-]+)+$ &&
        "$server" =~ ^https://[a-zA-Z0-9][a-zA-Z0-9.-]*(:[0-9]+)?(/[a-zA-Z0-9_.-]+)*$ &&
        "$revision" =~ ^[0-9a-f]{40}$ &&
        "${GCP_VM_HOST:-}" =~ ^[a-zA-Z0-9][a-zA-Z0-9.-]*$ &&
        "${GCP_VM_USER:-}" =~ ^[a-z_][a-z0-9_-]*$ ]]; then
    echo 'Invalid repository, branch, revision, or VM connection settings.' >&2
    exit 1
fi
test -n "$token" || { echo 'Missing repository job token.' >&2; exit 1; }
test -n "${GCP_SSH_PRIVATE_KEY:-}" && test -n "${GCP_SSH_KNOWN_HOSTS:-}" || {
    echo 'Set GCP_SSH_PRIVATE_KEY and GCP_SSH_KNOWN_HOSTS.' >&2
    exit 1
}

bundle_dir=$(mktemp -d)
trap 'rm -rf -- "$bundle_dir"' EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
if [[ "$credential_mode" == file ]]; then
    test -s "$GCP_SSH_PRIVATE_KEY" && test -s "$GCP_SSH_KNOWN_HOSTS" || {
        echo 'GitLab SSH credentials must be nonempty file variables.' >&2
        exit 1
    }
    install -m 600 "$GCP_SSH_PRIVATE_KEY" "$bundle_dir/key"
    install -m 600 "$GCP_SSH_KNOWN_HOSTS" "$bundle_dir/known_hosts"
else
    printf '%s\n' "$GCP_SSH_PRIVATE_KEY" > "$bundle_dir/key"
    printf '%s\n' "$GCP_SSH_KNOWN_HOSTS" > "$bundle_dir/known_hosts"
fi

# The token travels through encrypted stdin, never SSH arguments or Git remote URLs.
# Read the complete function before package installers can consume stdin.
{
    printf 'export DEPLOY_REPO_TOKEN=%q\n' "$token"
    printf 'export DEPLOY_REPO_SERVER=%q\n' "$server"
    printf 'export DEPLOY_REPO_USERNAME=%q\n' "$username"
    printf 'bootstrap_vm() {\n'
    cat deploy/bootstrap-vm.sh
    printf '\n}\nbootstrap_vm "$@" </dev/null\n'
} | ssh \
    -i "$bundle_dir/key" \
    -o IdentitiesOnly=yes \
    -o BatchMode=yes \
    -o StrictHostKeyChecking=yes \
    -o UserKnownHostsFile="$bundle_dir/known_hosts" \
    -o ConnectTimeout=20 \
    -o ServerAliveInterval=30 \
    -o ServerAliveCountMax=10 \
    "$GCP_VM_USER@$GCP_VM_HOST" \
    "bash -s -- '$repository' '$branch' '$revision'"
