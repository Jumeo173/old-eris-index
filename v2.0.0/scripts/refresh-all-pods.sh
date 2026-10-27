#!/usr/bin/env bash
# Форсирует reload storage-config ВСЕХ кластеров на ВСЕХ pod'ах.
# Использование:
#   ./refresh-all-pods.sh status
#   ./refresh-all-pods.sh refresh
set -euo pipefail

NAMESPACE="${NAMESPACE:-default}"
LABEL="${LABEL:-app=old-eris-index}"
ACTION="${1:-refresh}"

state_url()   { echo "http://localhost:8080/api/v1/admin/storage-config"; }
refresh_url() { echo "http://localhost:8080/api/v1/admin/storage-config/refresh"; }

print_pod_state() {
  local pod="$1"
  echo -n "$pod: "
  kubectl -n "$NAMESPACE" exec "$pod" -- curl -sf "$(state_url)" \
    | jq -c '[.[] | {name, ready, version, fingerprint, size}]' \
    || echo "CALL FAILED"
}

refresh_pod() {
  local pod="$1"
  echo -n "$pod: "
  kubectl -n "$NAMESPACE" exec "$pod" -- curl -sf -X POST "$(refresh_url)" \
    | jq -c '{allReady, clusters: [.clusters[] | {name, ready, version, fingerprint, size}]}' \
    || echo "REFRESH FAILED"
}

pods=$(kubectl -n "$NAMESPACE" get pods -l "$LABEL" -o name)
if [[ -z "$pods" ]]; then
  echo "No pods found for label $LABEL in namespace $NAMESPACE" >&2
  exit 1
fi

if [[ "$ACTION" == "refresh" ]]; then
  echo "=== BEFORE ==="
  while read -r p; do print_pod_state "$p"; done <<< "$pods"
  echo
  echo "=== REFRESH (all clusters) ==="
  while read -r p; do refresh_pod "$p"; done <<< "$pods"
  echo
  echo "=== AFTER ==="
  while read -r p; do print_pod_state "$p"; done <<< "$pods"
else
  while read -r p; do print_pod_state "$p"; done <<< "$pods"
fi
