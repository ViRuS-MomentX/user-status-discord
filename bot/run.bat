@echo off
rem chcp 65001 и флаги кодировки нужны, чтобы русские сообщения
rem не превращались в консоли Windows в кракозябры.
chcp 65001 >nul
java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar voice-bridge-bot.jar %*
pause
