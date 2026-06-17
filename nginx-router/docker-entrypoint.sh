#!/bin/sh
set -e

: "${UPSTREAM_FRONTEND:=catapult-web:8081}"
: "${UPSTREAM_BACKEND:=catapult-api:8080}"
: "${UPSTREAM_MAINTENANCE:=catapult-maintenance:80}"
: "${FORWARDED_PROTO:=\$scheme}"

export UPSTREAM_FRONTEND UPSTREAM_BACKEND UPSTREAM_MAINTENANCE FORWARDED_PROTO

envsubst '${UPSTREAM_FRONTEND} ${UPSTREAM_BACKEND} ${UPSTREAM_MAINTENANCE} ${FORWARDED_PROTO}' \
    < /etc/nginx/nginx.conf.template \
    > /etc/nginx/conf.d/default.conf

exec "$@"
