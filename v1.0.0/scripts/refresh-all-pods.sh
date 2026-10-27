#!/usr/bin/env bash
set -euo pipefail
NAMESPACE="${NAMESPACE:-default}"
LABEL="${LABEL:-app=old-eris-index}"
ENDPOINT="/api/v1/admin/storage-config"
ACTION="${1:-refresh}"

print_state() {
  kubectl -n "$NAMESPACE" get pods -l "$LABEL" -o name | while read -r p; do
    echo -n "$p: "
    kubectl -n "$NAMESPACE" exec "$p" -- curl -sf "localhost:8080$ENDPOINT" \
      | jq -c '{version, fingerprint, size, error}' || echo "CALL FAILED"
  done
}
if [[ "$ACTION" == "refresh" ]]; then
  echo "=== BEFORE ==="; print_state; echo
  echo "=== REFRESH ==="
  kubectl -n "$NAMESPACE" get pods -l "$LABEL" -o name | while read -r p; do
    echo -n "$p: "
    kubectl -n "$NAMESPACE" exec "$p" -- curl -sf -X POST "localhost:8080$ENDPOINT/refresh" \
      | jq -c '{version, fingerprint, size, error}' || echo "REFRESH FAILED"
  done
  echo; echo "=== AFTER ==="; print_state
else
  print_state
fi
