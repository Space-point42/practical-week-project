#!/usr/bin/env bash
# =====================================================================
#  启动前端开发服务器（Linux / macOS / Git Bash）
#
#  首次运行会自动安装依赖（npm install），需要几分钟。
#  开发服务器会把 /api 请求代理到 http://localhost:8080，
#  因此请先启动后端。
# =====================================================================
set -euo pipefail

cd "$(dirname "$0")/../frontend"

if [ ! -d node_modules ]; then
  echo
  echo "[提示] 未找到 node_modules，正在安装依赖……"
  npm install
fi

echo
echo "前端启动中，访问地址 http://localhost:5173"
echo "按 Ctrl+C 停止服务。"
echo

exec npm run dev
