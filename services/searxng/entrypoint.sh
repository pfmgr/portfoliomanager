#!/bin/sh
set -u

CONFIG_PATH=${CONFIG_PATH:-/etc/searxng}
CUSTOM_DIR="/usr/local/searxng/portfoliomanager"
CUSTOM_SETTINGS="$CUSTOM_DIR/settings.yml"
CUSTOM_LIMITER="$CUSTOM_DIR/limiter.toml"

mkdir -p "$CONFIG_PATH"

if [ -f "$CUSTOM_SETTINGS" ]; then
  cp -pfT "$CUSTOM_SETTINGS" "$CONFIG_PATH/settings.yml"
  chown searxng:searxng "$CONFIG_PATH/settings.yml" 2>/dev/null || true
fi

if [ -f "$CUSTOM_LIMITER" ]; then
  cp -pfT "$CUSTOM_LIMITER" "$CONFIG_PATH/limiter.toml"
  chown searxng:searxng "$CONFIG_PATH/limiter.toml" 2>/dev/null || true
fi

exec /usr/local/searxng/entrypoint.sh "$@"
