@echo off
rem chcp 65001 и флаги кодировки нужны, чтобы русские сообщения
rem не превращались в консоли Windows в кракозябры.
rem --enable-native-access разрешает доступ к нативным библиотекам:
rem без него Java сыплет предупреждениями, а в будущих версиях просто запретит.
chcp 65001 >nul
java --enable-native-access=ALL-UNNAMED -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar voice-bridge-bot.jar %*
pause
