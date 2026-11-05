#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
export DOCKER_HOST=unix:///run/docker-eval.sock
exec /usr/libexec/docker/cli-plugins/docker-compose --project-directory "$project_dir" -p bemodel "$@"
