#!/usr/bin/env bash
# 一键启动：栖云酒店预订管理系统
# 依赖：JDK 8+（本机 JDK8 或任意更高版本均可运行 Spring Boot 2.7）
# 构建：优先使用本机 Maven（~/tools 或 PATH），否则用项目自带 mvnw 自动下载
set -euo pipefail
cd "$(dirname "$0")"

# 1. 定位 JDK（优先 1.8，其次系统默认）
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 1.8 2>/dev/null || /usr/libexec/java_home 2>/dev/null || true)"
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "错误：未找到 JDK 8+，请先安装 JDK（https://adoptium.net）" >&2
  exit 1
fi
export JAVA_HOME
echo "使用 JDK: $JAVA_HOME"

# 2. 定位 Maven
MVN=""
if [ -x "$HOME/tools/apache-maven-3.9.16/bin/mvn" ]; then MVN="$HOME/tools/apache-maven-3.9.16/bin/mvn"
elif command -v mvn >/dev/null 2>&1; then MVN="mvn"
fi

# 3. 构建（jar 不存在或源码有更新时才重建）
NEED_BUILD=false
[ ! -f target/hotel-booking.jar ] && NEED_BUILD=true
if [ "$NEED_BUILD" = false ] && find src pom.xml -newer target/hotel-booking.jar 2>/dev/null | grep -q .; then
  NEED_BUILD=true
fi
if [ "$NEED_BUILD" = true ]; then
  if [ -n "$MVN" ]; then "$MVN" -B -ntp -q -DskipTests package
  else ./mvnw -B -ntp -q -DskipTests package
  fi
fi

# 4. 启动（首次启动自动初始化演示数据，约 5~10 秒）
echo "启动中: http://localhost:8080  （接口文档 http://localhost:8080/swagger-ui.html）"
exec "$JAVA_HOME/bin/java" -jar target/hotel-booking.jar
