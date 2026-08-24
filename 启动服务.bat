@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo ========================================
echo   gemini-web2api 启动中...
echo ========================================
echo.
python -m gemini_web2api
echo.
echo 服务已停止，按任意键退出...
pause >nul
