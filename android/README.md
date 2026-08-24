# gemini-web2api Android

把 gemini-web2api 打包成安卓 App：内嵌 Python（[Chaquopy](https://chaquo.com/chaquopy/)），
前台服务保活，在手机本机提供 OpenAI 兼容 API（默认 `http://127.0.0.1:8081/v1`），
供 ChatBox、Cherry Studio 等同机客户端调用。

## 云端构建（推荐，无需本地环境）

仓库已带 GitHub Actions 工作流 `.github/workflows/android.yml`，推送后自动构建：

- push 到 `main`/`master` → 构建并在 Actions 页面的 Artifacts 里产出 APK
- 推送 `v*` 标签（如 `git tag v1.1.0 && git push origin v1.1.0`）→ APK 附带在 GitHub Release 上
- 也可以在 Actions 页面手动触发（workflow_dispatch）

产物：`gemini-web2api-android.apk`（release 构建，debug 签名，可直接侧载安装）。

技术栈版本（在 CI 中固定）：Chaquopy 17.0.0 + Python 3.12 + AGP 8.5.2 + Gradle 8.9 + JDK 17，
ABI 为 `arm64-v8a`（真机）和 `x86_64`（模拟器）。

## 本地构建（可选）

需要 Android Studio 或命令行环境：

```bash
python android/sync_python.py   # 把根目录 gemini_web2api/ 同步进工程
cd android
gradle assembleRelease          # 或用 Android Studio 打开 android/ 目录
```

注意 `sync_python.py` 必须先跑：工程本身不提交 Python 包副本，
Android 源码目录里只有入口 `server_runner.py`。

## 使用

1. 安装并打开 App，点 **启动服务**（首次会请求通知权限，建议允许）
2. 同机客户端配置：
   - Base URL：`http://127.0.0.1:8081/v1`（界面会显示实际地址）
   - API Key：默认 `api_keys` 为空，随便填；配置后填对应 key
   - Model：`gemini-3.6-flash` 等，与桌面版一致
3. 建议点一次 **忽略电池优化**，防止国产 ROM 杀后台
4. 需要 Pro 模型 / 图片上传时，把浏览器导出的 cookie 粘贴到 **cookie.txt** 编辑框并保存，
   立即生效无需重启；`config.json` 里的代理等设置修改保存后会自动重启服务

生成的文件都在应用私有目录 `<data>/com.geminiweb2api/files/` 下：
`config.json`、`cookie.txt`、SQLite 缓存。

### 局域网访问

把 `config.json` 中的 `host` 改为 `"0.0.0.0"` 并保存，局域网设备即可通过
`http://<手机IP>:8081/v1` 访问。注意：此时若 `api_keys` 为空等于开放无鉴权接口，
建议同时配置 `api_keys`。

## 与桌面版的差异

- 默认 `host` 为 `127.0.0.1`（桌面版是 `0.0.0.0`），避免误开放
- 默认 `cookie_file` 指向应用目录下的 `cookie.txt`
- 模型、接口、流式输出与桌面版完全一致（同一份代码）

## 已知注意事项

- Python 3.12 构建仅含 64 位 ABI；2017 年以前的 32 位老设备无法安装
- 部分客户端默认禁止明文 HTTP，若连不上本机 `http://127.0.0.1`，
  请在客户端中开启"允许明文/局域网请求"之类的选项
- 频率限制、上游协议变更等风险与桌面版相同；手机运营商网络下若被上游拒绝，
  在 `config.json` 配置代理
