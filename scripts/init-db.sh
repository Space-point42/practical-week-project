#!/usr/bin/env bash
# =====================================================================
#  知识管理系统 —— 数据库初始化（Linux / macOS / Git Bash）
#
#  作用：创建 kms 数据库与 20 张表。
#  注意：脚本会先删除同名库再重建，请勿在有数据的库上执行。
#
#  用法：
#      bash scripts/init-db.sh
#      bash scripts/init-db.sh root 你的密码
# =====================================================================
set -euo pipefail

cd "$(dirname "$0")/.."

DB_USER="${1:-root}"
DB_PASS="${2:-}"

run_mysql() {
  if [ -n "$DB_PASS" ]; then
    mysql -u"$DB_USER" -p"$DB_PASS" "$@"
  else
    mysql -u"$DB_USER" "$@"
  fi
}

echo
echo "[1/2] 正在创建数据库与表结构……"
run_mysql < backend/src/main/resources/db/schema.sql

echo
echo "[2/2] 完成。已创建的表："
run_mysql -e "USE kms; SHOW TABLES;"

echo
echo "接下来启动后端，首次启动会自动导入本体、演示数据与构建检索索引。"
