# Biến môi trường và setting của kiro-cli v3 (ghi chú cá nhân)

Nguồn: tài liệu Kiro, chuỗi tìm thấy trong `kiro-cli.exe` 3.1.0, và thử trực tiếp trên máy.
Độ chắc chắn: **[doc]** có trong tài liệu chính thức, **[bin]** thấy tên cùng ngữ cảnh trong binary,
**[tên]** chỉ thấy tên biến, chưa rõ tác dụng. Mỗi biến `KIRO_*` trong binary đều có bản `Q_*` cũ đi kèm (tương thích Amazon Q).

Đặt biến cho plugin ở **Settings → Tools → Kiro → Environment**, mỗi dòng `KEY=VALUE`. Có hiệu lực ở lần khởi động kiro-cli kế tiếp (Restart).

## Hay dùng với plugin này

| Biến | Tác dụng | Ghi chú |
|---|---|---|
| `KIRO_LOG_LEVEL` | Mức log (`debug`, `info`…) | [bin] Đã dùng khi debug acp |
| `KIRO_CHAT_LOG_FILE` | Ghi log ra file | [bin] Thử trên máy công ty: kiểm tra file có được tạo |
| `KIRO_LOG_STDOUT` | Đưa log ra stdout | [bin] **Đừng bật trong ACP**: stdout là kênh JSON-RPC, làm hỏng giao thức |
| `KIRO_LOG_NO_COLOR` | Tắt màu trong log | [doc] |
| `KIRO_HOME` | Đổi thư mục `~/.kiro` (agents, skills, steering, settings, sessions) | [doc] Tiện tách profile công việc / cá nhân |
| `KIRO_DATA_DIR` | Thư mục dữ liệu (sqlite, knowledge base) | [bin] |
| `KIRO_API_KEY` | Đăng nhập không cần trình duyệt (CI, headless) | [doc] Chỉ gói trả phí; thấp hơn phiên `kiro-cli login` trong thứ tự ưu tiên |
| `KIRO_NO_AUTO_UPDATE` | Tắt tự cập nhật | [bin] Doc ghi setting `app.disableAutoupdates` |
| `KIRO_DISABLE_TELEMETRY` | Tắt telemetry | [bin] |
| `KIRO_AGENT_CONFIG_DIR` | Thư mục agent config khác | [bin] |
| `KIRO_REQUIRE_MCP_STARTUP_TIMEOUT_SECS` | Chờ MCP server khởi động tối đa bao lâu | [bin] Hợp với máy công ty chậm |
| `KIRO_SUBAGENT_STALL_TIMEOUT_MS` | Sau bao lâu subagent im lặng thì coi là kẹt | [bin] Cùng với setting `api.subagentTimeout` |
| `KIRO_CLI_ACP_CLIENT_NAME` | Tên client ACP báo cho kiro-cli | [bin] |
| `KIRO_FORCE_INTERACTIVE` | Ép chế độ tương tác | [bin] |

## Engine, runtime và đường dẫn

| Biến | Tác dụng |
|---|---|
| `KIRO_AGENT_ENGINE` | Chọn engine (`v1`, `v2`, `v3`/kas) [tên]. Cờ dòng lệnh `--agent-engine` và setting `acp.agentEngine` ghi đè |
| `KIRO_KAS_NODE_PATH`, `KIRO_KAS_SERVER_PATH` | Node và server của engine v3. Thiếu thì lỗi "embedded node binary is unavailable" [bin] |
| `KIRO_KAS_ENDPOINT`, `KIRO_KAS_CONTROL_PLANE_ENDPOINT`, `KIRO_KAS_REGION` | Điểm kết nối của dịch vụ kas [tên] |
| `KIRO_CHAT_CLI_BIN`, `KIRO_CHAT_CLI_FALLBACK_BIN`, `KIRO_CHAT_CLI_BIN_IDENTITY` | Binary runtime thật mà launcher gọi (`run\chat-cli-*.exe`) [bin] |
| `KIRO_RUNTIME_DIR` | Thư mục chứa runtime (mới ở 3.x) [tên] |
| `KIRO_SKIP_BINARY_PINNING` | Bỏ qua việc ghim phiên bản binary [tên] |
| `KIRO_CHAT_UI`, `KIRO_UI_MODE` | Chọn giao diện TUI (bun + `tui.js`) hay cũ. TUI không dùng với `--agent-engine=v1` [bin] |
| `KIRO_FEED_URL`, `KIRO_BUNDLED_FEED_FILE`, `KIRO_NO_REMOTE_CHANGELOG` | Nguồn tin / changelog [tên] |
| `KIRO_REMOTE_SESSIONS_ENDPOINT`, `CLOUD_CONFIG_ENDPOINT` | Mặc định `https://app.kiro.dev` (thấy trong log) |

## Shell và terminal

`KIRO_SHELL`, `KIRO_DEBUG_SHELL`, `KIRO_CHAT_SHELL`, `KIRO_ZDOTDIR`, `KIRO_TERM`, `KIRO_PARENT`, `KIRO_SET_PARENT`,
`PROCESS_LAUNCHED_BY_KIRO`, `KIRO_USING_ZSH_AUTOSUGGESTIONS`: nhóm tích hợp shell kế thừa từ Q CLI [tên].
**Không phải bộ chọn shell** cho tool chạy lệnh. Trên Windows, shell cho lệnh được chọn theo `PSModulePath`:
có thì `pwsh` (nếu `where.exe pwsh` thấy) hoặc `powershell` với `-Command`, không thì `cmd /C`. Ngoài Windows dùng `$SHELL -c`.
Đây là logic trong `tui.js`; binary Rust có cùng chuỗi nên nhiều khả năng giống nhau (chưa kiểm chứng cho tool shell của ACP).
Có [issue xin chọn Git Bash](https://github.com/kirodotdev/Kiro/issues/9537).

`KIRO_TERMINAL_THEME`, `KIRO_TERMINAL_BANNER`, `KIRO_TUI_FORCE_COLOR`, `KIRO_DISABLE_TRUECOLOR`: giao diện terminal [tên].

## Bảo mật và chính sách (máy công ty)

| Biến | Ghi chú |
|---|---|
| `KIRO_MANDATORY_MCPS` | Danh sách MCP bắt buộc (chính sách tổ chức) [tên] |
| `KIRO_DEFAULT_SETTINGS`, `KIRO_DEFAULT_SETTINGS_SKIP_OWNER_CHECK` | File setting mặc định do quản trị đặt; có bước kiểm tra chủ sở hữu file [bin] |
| `KIRO_TRUST_CLASSIFIER_MODE`, `KIRO_TRUST_CLASSIFIER_SHADOW` | Bộ phân loại độ tin cậy khi tự duyệt tool [tên] |
| `KIRO_ELEVATED_PRIVILEGES` | [tên] |
| `KIRO_ENABLED_FEATURES`, `KIRO_ROLLOUT_FEATURES`, `KIRO_FEATURE_*_ENABLED` | Bật tắt tính năng thử nghiệm (cloud config, universal background tool…) [tên] |

## Chỉ để thử nghiệm (đừng dùng)

`KIRO_TEST_*`, `KIRO_MOCK_CHAT_RESPONSE`, `KIRO_RECORD_API_REQUESTS_PATH`, `KIRO_RECORD_API_RESPONSES_PATH`,
`KIRO_COVERAGE*`, `KIRO_FAKE_IS_REMOTE`, `KIRO_CODESPACES`, `KIRO_INTERNAL`, `KIRO_MIDWAY_EXPIRY`.

## Khác biệt giữa 2.32 và 3.1 (theo chuỗi trong binary)

- Mới ở 3.1: `KIRO_RUNTIME_DIR`, `KIRO_AGENT_UPGRADE_NOTICE`, `KIRO_DEFAULT_SETTINGS(_SKIP_OWNER_CHECK)`, `KIRO_ELEVATED_PRIVILEGES`,
  `KIRO_FEATURE_UNIVERSAL_BACKGROUND_TOOL_ENABLED`, `KIRO_LAUNCH_3_0`, `KIRO_TRUST_CLASSIFIER_MODE`, `KIRO_TEST_POLICY_HOME`, `KIRO_TEST_SYSTEM_ROOT`.
- Mất ở 3.1: `KIRO_FEATURE_BACKGROUND_EXECUTION_ENABLED`, `KIRO_LOCAL_SANDBOX_ROLLOUT_ENABLED`, `KIRO_ROLLOUT_FORCE_NIGHTLY`, `KIRO_STANDALONE_CUTOVER`.

## Setting qua `kiro-cli settings <key> <value>`

Lưu ở `~/.kiro/settings/cli.json` (tương ứng một số biến ở trên):
`app.disableAutoupdates`, `acp.agentEngine` (`v1`/`v2`/`v3`), `api.subagentTimeout`, `chat.enableKnowledge`, `mcp.loadedBefore`.
Xem hiện tại: `kiro-cli settings list`.

## Quan sát khi thử

- `kiro-cli acp` không in gì cho tới khi nhận dòng `initialize` trên stdin. Giữ stdin mở khi thử bằng tay (`(echo '...'; sleep 60) | kiro-cli acp`).
- Log của 3.1 ghi `resolved kiro-cli acp engine ... engine=kas`: engine thực tế là kas (v3) dù `--help` ghi mặc định v2.
- Khi stdin đóng, engine có thể không thoát trong 5 giây và kiro-cli in "the v3 engine did not exit within 5 seconds". Vô hại nhưng có thể để lại tiến trình con.
- Khởi động trên máy này: `initialize` ~3 giây, `session/new` ~6 giây.

## Nguồn

- [Kiro CLI settings](https://kiro.dev/docs/cli/reference/settings)
- [Kiro CLI configuration](https://kiro.dev/docs/cli/chat/configuration/)
- [Built-in tools](https://kiro.dev/docs/reference/built-in-tools/)
