package io.brace;

public class OpsDashboard {

    public static String html(String opsSecret) {
        return """
            <!DOCTYPE html>
            <html>
            <head>
            <title>Brace Dashboard</title>
            <meta charset="UTF-8">
            <style>
            * { margin: 0; padding: 0; box-sizing: border-box; }
            body { background: #1a1a2e; color: #e0e0e0; font-family: 'Menlo', 'Consolas', monospace; font-size: 13px; padding: 20px; }
            h1 { color: #e94560; font-size: 20px; margin-bottom: 4px; }
            h2 { color: #0f3460; background: #e94560; display: inline-block; padding: 2px 10px; margin: 16px 0 8px 0; font-size: 13px; }
            .header { margin-bottom: 16px; border-bottom: 1px solid #333; padding-bottom: 12px; }
            .header span { color: #888; margin-right: 20px; }
            .stats-row { display: flex; gap: 16px; margin-bottom: 16px; flex-wrap: wrap; }
            .stat-card { background: #16213e; border: 1px solid #0f3460; padding: 12px 20px; min-width: 140px; }
            .stat-card .label { color: #888; font-size: 11px; text-transform: uppercase; }
            .stat-card .value { color: #e94560; font-size: 22px; font-weight: bold; margin-top: 4px; }
            table { border-collapse: collapse; width: 100%%; margin-bottom: 16px; }
            th { text-align: left; color: #e94560; border-bottom: 1px solid #333; padding: 6px 12px; font-size: 11px; text-transform: uppercase; }
            td { padding: 5px 12px; border-bottom: 1px solid #222; }
            tr:hover td { background: #16213e; }
            .sparkline { display: flex; align-items: flex-end; gap: 2px; height: 60px; margin: 8px 0; }
            .sparkline .bar { background: #e94560; min-width: 6px; flex: 1; transition: height 0.3s; }
            .sparkline .bar:hover { background: #ff6b81; }
            .error-text { color: #ff6b6b; }
            .ok-text { color: #51cf66; }
            .muted { color: #666; }
            .two-col { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
            @media (max-width: 800px) { .two-col { grid-template-columns: 1fr; } }
            .btn { background: #0f3460; color: #e0e0e0; border: 1px solid #e94560; padding: 4px 12px; cursor: pointer; font-family: inherit; font-size: 12px; }
            .btn:hover { background: #e94560; color: #0f3460; }
            .btn-sm { padding: 2px 8px; font-size: 11px; }
            .section-header { display: flex; align-items: center; gap: 12px; }
            .stack-trace { max-height: 200px; overflow-y: auto; background: #111; padding: 8px; margin-top: 4px; font-size: 11px; white-space: pre-wrap; word-break: break-all; border: 1px solid #222; }
            .expandable { cursor: pointer; }
            .expandable:hover { color: #e94560; }
            .tab-bar { display: flex; gap: 0; margin-bottom: 0; }
            .tab { background: #16213e; border: 1px solid #333; border-bottom: none; padding: 4px 16px; cursor: pointer; color: #888; }
            .tab.active { background: #1a1a2e; color: #e94560; border-color: #e94560; border-bottom: 1px solid #1a1a2e; margin-bottom: -1px; z-index: 1; position: relative; }
            .tab-content { border-top: 1px solid #e94560; padding-top: 8px; }
            </style>
            </head>
            <body>
            <div id="app">Loading...</div>
            <script>
            const KEY = '%s';
            const app = document.getElementById('app');
            let data = null;
            let persistedErrors = [];
            let errorTab = 'unresolved';

            async function fetchData() {
                try {
                    const [statusRes, unresolvedRes, resolvedRes] = await Promise.all([
                        fetch('/ops/status', { headers: { 'X-Ops-Key': KEY } }),
                        fetch('/ops/errors', { headers: { 'X-Ops-Key': KEY } }),
                        fetch('/ops/errors?status=resolved', { headers: { 'X-Ops-Key': KEY } })
                    ]);
                    if (!statusRes.ok) { app.innerHTML = '<p class="error-text">Failed to load: ' + statusRes.status + '</p>'; return; }
                    data = await statusRes.json();
                    const unresolved = unresolvedRes.ok ? await unresolvedRes.json() : [];
                    const resolved = resolvedRes.ok ? await resolvedRes.json() : [];
                    persistedErrors = [...unresolved, ...resolved];
                    render();
                } catch (e) {
                    app.innerHTML = '<p class="error-text">Error: ' + e.message + '</p>';
                }
            }

            async function clearCache() {
                await fetch('/ops/cache/clear', { method: 'POST', headers: { 'X-Ops-Key': KEY } });
                fetchData();
            }

            async function resolveError(id) {
                await fetch('/ops/errors/' + id + '/resolve', { method: 'POST', headers: { 'X-Ops-Key': KEY } });
                fetchData();
            }

            function showErrorTab(tab) {
                errorTab = tab;
                render();
            }

            function render() {
                if (!data) return;
                const a = data.app || {};
                const mem = data.memory || {};
                const http = data.http || {};
                const errors = data.errors || {};
                const jobs = data.jobs || {};
                const ts = data.timeseries || {};
                const codes = http.statusCodes || {};
                const cacheInfo = data.cache || null;

                const totalReqs = Object.values(codes).reduce((s, v) => s + v, 0);
                const errCount = (codes[500] || 0) + (codes[502] || 0) + (codes[503] || 0);
                const errRate = totalReqs > 0 ? ((errCount / totalReqs) * 100).toFixed(1) : '0.0';

                let html = '';

                // Header
                html += '<div class="header">';
                html += '<h1>Brace Dashboard</h1>';
                html += '<span>Uptime: ' + esc(a.uptime || '-') + '</span>';
                html += '<span>Java: ' + esc(a.javaVersion || '-') + '</span>';
                html += '<span>Started: ' + esc(a.startedAt || '-') + '</span>';
                html += '</div>';

                // Stat cards
                html += '<div class="stats-row">';
                html += statCard('Requests', totalReqs.toLocaleString());
                html += statCard('Error Rate', errRate + '%%');
                html += statCard('Heap Used', mem.heapUsedMB + ' MB');
                html += statCard('Heap Max', mem.heapMaxMB + ' MB');
                if (cacheInfo) {
                    html += statCard('Cache', cacheInfo.entries + ' entries');
                }
                html += '</div>';

                // Sparkline
                const minutes = ts.minutes || [];
                if (minutes.length > 0) {
                    html += '<h2>Requests / Minute</h2>';
                    const maxReq = Math.max(1, ...minutes.map(m => m.requests));
                    html += '<div class="sparkline">';
                    for (const m of minutes) {
                        const pct = (m.requests / maxReq) * 100;
                        html += '<div class="bar" style="height:' + Math.max(2, pct) + '%%"'
                            + ' title="' + m.requests + ' reqs, ' + m.avgMs + ' ms avg @ ' + m.ts + '"></div>';
                    }
                    html += '</div>';
                }

                html += '<div class="two-col">';

                // Routes table
                html += '<div>';
                html += '<h2>Slowest Routes</h2>';
                html += '<table><tr><th>Route</th><th>Count</th><th>Avg ms</th></tr>';
                for (const r of (http.slowestRoutes || [])) {
                    html += '<tr><td>' + esc(r.route) + '</td><td>' + r.count + '</td><td>' + r.avgMs + '</td></tr>';
                }
                html += '</table></div>';

                // In-memory errors
                html += '<div>';
                html += '<h2>Recent Errors (In-Memory)</h2>';
                const recentErrs = errors.recent || [];
                if (recentErrs.length === 0) {
                    html += '<p class="ok-text">No errors</p>';
                } else {
                    html += '<table><tr><th>Type</th><th>Route</th><th>Count</th><th>Last Seen</th></tr>';
                    for (const e of recentErrs) {
                        html += '<tr><td class="error-text">' + esc(e.type) + '</td><td>' + esc(e.route || '-') + '</td><td>' + e.count + '</td><td>' + esc(e.lastSeen || '-') + '</td></tr>';
                    }
                    html += '</table>';
                }
                html += '</div>';

                html += '</div>'; // two-col

                // Persisted errors
                html += '<div class="section-header"><h2>Error Tracking</h2></div>';
                html += '<div class="tab-bar">';
                html += '<div class="tab' + (errorTab === 'unresolved' ? ' active' : '') + '" onclick="showErrorTab('unresolved')" >Unresolved (' + persistedErrors.filter(e => !e.resolvedAt).length + ')</div>';
                html += '<div class="tab' + (errorTab === 'resolved' ? ' active' : '') + '" onclick="showErrorTab('resolved')">Resolved (' + persistedErrors.filter(e => e.resolvedAt).length + ')</div>';
                html += '</div>';
                html += '<div class="tab-content">';
                const filtered = persistedErrors.filter(e => errorTab === 'resolved' ? e.resolvedAt : !e.resolvedAt);
                if (filtered.length === 0) {
                    html += '<p class="' + (errorTab === 'unresolved' ? 'ok-text' : 'muted') + '">None</p>';
                } else {
                    html += '<table><tr><th>Type</th><th>Route</th><th>Count</th><th>First Seen</th><th>Last Seen</th><th></th></tr>';
                    for (const e of filtered) {
                        html += '<tr>';
                        html += '<td class="error-text expandable" onclick="this.parentElement.nextElementSibling.style.display=this.parentElement.nextElementSibling.style.display==='none'?'table-row':'none'">' + esc(e.errorType) + '</td>';
                        html += '<td>' + esc(e.route || '-') + '</td>';
                        html += '<td>' + e.occurrenceCount + '</td>';
                        html += '<td>' + esc(e.firstSeen || '-') + '</td>';
                        html += '<td>' + esc(e.lastSeen || '-') + '</td>';
                        if (!e.resolvedAt) {
                            html += '<td><button class="btn btn-sm" onclick="resolveError(' + e.id + ')">Resolve</button></td>';
                        } else {
                            html += '<td class="muted">' + esc(e.resolvedAt) + '</td>';
                        }
                        html += '</tr>';
                        html += '<tr style="display:none"><td colspan="6"><div class="stack-trace">' + esc(e.stackTrace || 'No stack trace') + '</div><div style="margin-top:4px;color:#888">' + esc(e.message || '') + '</div></td></tr>';
                    }
                    html += '</table>';
                }
                html += '</div>';

                // Jobs table
                const scheduled = jobs.scheduled || [];
                if (scheduled.length > 0) {
                    html += '<h2>Scheduled Jobs</h2>';
                    html += '<table><tr><th>Name</th><th>Schedule</th><th>Status</th><th>Last Run</th><th>Duration</th><th>Failures</th></tr>';
                    for (const j of scheduled) {
                        const statusCls = j.lastStatus === 'ok' ? 'ok-text' : j.lastStatus === 'error' ? 'error-text' : 'muted';
                        html += '<tr><td>' + esc(j.name) + '</td><td>' + esc(j.schedule) + '</td>';
                        html += '<td class="' + statusCls + '">' + esc(j.lastStatus || 'pending') + '</td>';
                        html += '<td>' + esc(j.lastRun || '-') + '</td>';
                        html += '<td>' + j.lastDurationMs + ' ms</td>';
                        html += '<td>' + j.failCount + '</td></tr>';
                    }
                    html += '</table>';
                }

                // Cache details + control
                if (cacheInfo) {
                    html += '<div class="section-header"><h2>Cache</h2>';
                    html += '<button class="btn btn-sm" onclick="clearCache()">Clear All</button></div>';
                    html += '<div class="stats-row">';
                    html += statCard('Entries', cacheInfo.entries);
                    html += statCard('Counters', cacheInfo.counters);
                    html += statCard('Tags', cacheInfo.tags);
                    html += '</div>';
                }

                // Status codes
                html += '<h2>Status Codes</h2>';
                html += '<table><tr><th>Code</th><th>Count</th></tr>';
                for (const [code, count] of Object.entries(codes).sort((a,b) => a[0] - b[0])) {
                    html += '<tr><td>' + code + '</td><td>' + count + '</td></tr>';
                }
                html += '</table>';

                app.innerHTML = html;
            }

            function statCard(label, value) {
                return '<div class="stat-card"><div class="label">' + label + '</div><div class="value">' + value + '</div></div>';
            }

            function esc(s) {
                if (s == null) return '';
                return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
            }

            fetchData();
            setInterval(fetchData, 5000);
            </script>
            </body>
            </html>
            """.formatted(opsSecret);
    }
}
