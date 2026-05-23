#!/usr/bin/env bash
#
# scripts/dev-loop.sh
#
# Развёртывание текущего рабочего дерева на подключённое Android-устройство:
#  1) проверяет ADB и видит ли он девайс
#  2) собирает и устанавливает debug-APK
#  3) запускает приложение
#  4) стримит logcat с фильтром по нашему пакету (warn+), пишет в logs/<TS>.log
#  5) красным выделяет FATAL EXCEPTION / ANR
#
# Использование:
#   ./scripts/dev-loop.sh
#
set -euo pipefail

PKG_BASE="com.wikillm.android"
PKG_DEBUG="${PKG_BASE}.debug"
ACTIVITY="${PKG_BASE}.MainActivity"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOGS="$ROOT/logs"
mkdir -p "$LOGS"
TS=$(date +"%Y%m%d-%H%M%S")
LOGFILE="$LOGS/$TS.log"

red()    { printf "\033[1;31m%s\033[0m\n" "$*"; }
green()  { printf "\033[1;32m%s\033[0m\n" "$*"; }
yellow() { printf "\033[1;33m%s\033[0m\n" "$*"; }

# 0. ADB
if ! command -v adb >/dev/null 2>&1; then
    red "❌ adb не найден в PATH. Установи: brew install --cask android-platform-tools"
    exit 1
fi

# 1. Устройство
device_line=$(adb devices | tail -n +2 | grep -E "device$|emulator-.*device$" || true)
if [ -z "$device_line" ]; then
    red "❌ Нет подключённых устройств."
    yellow "   Проверь:"
    yellow "    • USB-кабель + 'Разрешить отладку' на телефоне"
    yellow "    • или Wi-Fi: 'adb connect <ip>:<port>'"
    yellow "    • либо запусти эмулятор"
    exit 1
fi
device_id=$(echo "$device_line" | awk '{print $1}' | head -1)
green "✅ Устройство: $device_id"

# 2. Сборка + установка
yellow "→ ./gradlew :app:installDebug"
cd "$ROOT"
./gradlew :app:installDebug --no-daemon --console=plain

# 3. Запуск
yellow "→ Перезапуск $PKG_DEBUG/$ACTIVITY"
adb -s "$device_id" shell am force-stop "$PKG_DEBUG" >/dev/null 2>&1 || true
adb -s "$device_id" shell am start -n "$PKG_DEBUG/$ACTIVITY"

# 4. Logcat
adb -s "$device_id" logcat -c
yellow "→ Logcat (W+) → $LOGFILE   Ctrl-C для выхода"

adb -s "$device_id" logcat *:W AndroidRuntime:E ActivityManager:E | tee "$LOGFILE" |
    while IFS= read -r line; do
        case "$line" in
            *"FATAL EXCEPTION"*)
                printf "\n\033[1;41m========== FATAL ==========\033[0m\n"
                red "$line"
                printf "\033[1;41m===========================\033[0m\n\n"
                ;;
            *"ANR in"*|*"Application is not responding"*)
                printf "\n\033[1;43m========== ANR ============\033[0m\n"
                red "$line"
                printf "\033[1;43m===========================\033[0m\n\n"
                ;;
        esac
    done
