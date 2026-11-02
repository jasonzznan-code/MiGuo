# Auth account-tail API shapes (live verified 2026-10-27)

Base: `/api/v1/auth`，头：`X-API-Key` +（需登录时）`Authorization: Bearer <access_token>`

## POST /password
Request: `{"old_password":"...","new_password":"..."}`
Success 200: `{"ok":true}` — 吊销该用户全部 token
Errors:
- 401 `{"detail":"原密码错误"}`
- 422 `{"detail":"密码至少 N 位"}`
- 401 `{"detail":"需要有效的 access token"}`（未带/无效 token）

## POST /logout
Request: `{"refresh_token":"...","everywhere":true}` （refresh_token 可选；everywhere 默认 false）
Success 200: `{"ok":true,"revoked":N}`
everywhere=true → 删除该 user_id 下全部 tokens

## DELETE /me
Request body: `{"password":"..."}`
Success 200: `{"ok":true,"user_id":"..."}` — 删 users + tokens，记忆数据保留
Errors:
- 401 `{"detail":"密码错误"}`
