#!/system/bin/sh
set -e
PKG=com.nexus.assistant
CFG=/data/data/$PKG/files/config.json
KEY=$(tr -d '\r\n' </data/adb/nexus/secrets/deepseek.key)
if [ -z "$KEY" ]; then
  echo "NO_KEY"
  exit 1
fi
# Prefer jq-less rewrite: if config missing, write minimal; else python/sed replace
if [ ! -f "$CFG" ]; then
  mkdir -p /data/data/$PKG/files
  cat >"$CFG" <<EOF
{"sims":[{"slot":0,"label":"卡1","carrier":"","number":"","policy":"ai"},{"slot":1,"label":"卡2","carrier":"","number":"","policy":"human"}],"llm":{"enabled":true,"model":"deepseek-v4-flash","api_key":"$KEY","base_url":"https://api.deepseek.com","max_msgs":24},"notify":{"enabled":false,"webhook_url":"","sms_enabled":true,"call_enabled":true}}
EOF
else
  # Replace api_key field or inject into llm object
  TMP=$(mktemp)
  if grep -q '"api_key"' "$CFG"; then
    sed "s/\"api_key\"[[:space:]]*:[[:space:]]*\"[^\"]*\"/\"api_key\": \"$KEY\"/" "$CFG" >"$TMP"
  else
    sed "s/\"llm\"[[:space:]]*:[[:space:]]*{/\"llm\": {\"api_key\": \"$KEY\", /" "$CFG" >"$TMP"
  fi
  mv "$TMP" "$CFG"
fi
OWNER=$(stat -c %u /data/data/$PKG)
GROUP=$(stat -c %g /data/data/$PKG)
chown "$OWNER:$GROUP" "$CFG"
chmod 600 "$CFG"
# Confirm without printing full key
TAIL=$(echo -n "$KEY" | tail -c 4)
echo "OK tail=$TAIL"
