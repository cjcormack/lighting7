package uk.me.cormack.lighting7.mcp

/**
 * The two HTML pages the MCP listener serves: the sign-in-and-consent form, and a plain error
 * page for a request that cannot be redirected back to its client.
 *
 * Server-rendered and self-contained — no script, no external asset — because this listener
 * serves nothing else: the React bundle lives on the LAN port and must stay there. Every value
 * written into the page goes through [esc].
 */
internal object McpSignInPage {

    fun signIn(
        requestId: String,
        clientName: String,
        deskName: String,
        error: String? = null,
        username: String = "",
    ): String = page("Allow ${esc(clientName)}?") {
        """
        <h1>Allow <strong>${esc(clientName)}</strong> to control the lights?</h1>
        <p class="lead">It will be able to run cues, apply looks and templates, set tempo and record
        into your show on <strong>${esc(deskName)}</strong>, as you. You can revoke it from
        Profile → Devices on the desk.</p>
        ${error?.let { """<p class="error" role="alert">${esc(it)}</p>""" } ?: ""}
        <form method="post" action="/oauth/authorize" autocomplete="on">
          <input type="hidden" name="request_id" value="${esc(requestId)}">
          <label>Desk username
            <input name="username" autocomplete="username" value="${esc(username)}" required autofocus>
          </label>
          <label>Password
            <input name="password" type="password" autocomplete="current-password" required>
          </label>
          <div class="actions">
            <button type="submit" name="action" value="deny" formnovalidate class="secondary">Deny</button>
            <button type="submit" name="action" value="allow">Sign in and allow</button>
          </div>
        </form>
        """
    }

    fun error(title: String, message: String): String = page(title) {
        """
        <h1>${esc(title)}</h1>
        <p class="lead">${esc(message)}</p>
        """
    }

    private fun page(title: String, body: () -> String): String = """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <meta name="color-scheme" content="light dark">
          <title>$title · lighting7</title>
          <style>
            :root { --bg: #f5f5f4; --card: #fff; --fg: #1c1917; --muted: #57534e; --accent: #b45309; --err: #b91c1c; --line: #d6d3d1; }
            @media (prefers-color-scheme: dark) { :root { --bg: #0c0a09; --card: #1c1917; --fg: #fafaf9; --muted: #a8a29e; --accent: #f59e0b; --err: #f87171; --line: #44403c; } }
            * { box-sizing: border-box; }
            body { margin: 0; min-height: 100vh; display: grid; place-items: center; background: var(--bg); color: var(--fg);
                   font: 16px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif; padding: 16px; }
            main { width: 100%; max-width: 420px; background: var(--card); border: 1px solid var(--line); border-radius: 12px; padding: 24px; }
            h1 { font-size: 1.25rem; margin: 0 0 8px; }
            .lead { color: var(--muted); margin: 0 0 20px; }
            .error { color: var(--err); margin: 0 0 16px; }
            label { display: block; font-size: .875rem; font-weight: 600; margin-bottom: 14px; }
            input { display: block; width: 100%; margin-top: 4px; padding: 10px 12px; font: inherit; color: inherit;
                    background: var(--bg); border: 1px solid var(--line); border-radius: 8px; }
            .actions { display: flex; gap: 8px; justify-content: flex-end; margin-top: 20px; }
            button { font: inherit; font-weight: 600; padding: 10px 16px; border-radius: 8px; border: 1px solid var(--accent);
                     background: var(--accent); color: #fff; cursor: pointer; }
            button.secondary { background: transparent; color: var(--fg); border-color: var(--line); }
          </style>
        </head>
        <body><main>${body()}</main></body>
        </html>
    """.trimIndent()

    fun esc(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }
}
