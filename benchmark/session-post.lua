-- POST scenario for run-session.sh; body/headers injected via environment.
wrk.method = "POST"
wrk.body = os.getenv("WRK_BODY") or ""
wrk.headers["Content-Type"] = os.getenv("WRK_CONTENT_TYPE") or "application/x-www-form-urlencoded"
local cookie = os.getenv("WRK_COOKIE")
if cookie and cookie ~= "" then
  wrk.headers["Cookie"] = cookie
end
