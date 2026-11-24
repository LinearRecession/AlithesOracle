(function () {
    "use strict";

    const STATUS_ORDER = ["ALL", "ACTIVE", "RESOLVED_YES", "RESOLVED_NO", "DISPUTED", "REJECTED", "UNRESOLVABLE"];
    const EVIDENCE_STAGES = ["ALL", "ASSESSED", "SKIPPED", "FAILED", "NEW"];

    const state = {
        system: null,
        health: null,
        questions: [],
        selectedStatusFilter: "ALL",
        selectedQuestionId: null,
        selectedEvidenceStage: "ALL",
        questionDetail: null
    };

    document.addEventListener("DOMContentLoaded", () => {
        initTabs();
        initAskForm();
        initQuestionsPanel();
        setDefaultQuestionWindow();
        loadSystemAndHealth();
        loadQuestions();
    });

    function initTabs() {
        document.querySelectorAll(".tab-btn").forEach((btn) => {
            btn.addEventListener("click", () => {
                const target = btn.dataset.tab;
                document.querySelectorAll(".tab-btn").forEach((b) => b.classList.toggle("active", b === btn));
                document.querySelectorAll(".tab-panel").forEach((panel) => {
                    panel.classList.toggle("active", panel.id === `tab-${target}`);
                });
                if (target === "questions") {
                    loadQuestions();
                } else if (target === "system") {
                    loadSystemAndHealth();
                }
            });
        });

        document.getElementById("refresh-system-btn").addEventListener("click", () => {
            loadSystemAndHealth();
            loadQuestions();
        });
    }

    async function fetchJson(url, options = {}) {
        const res = await fetch(url, options);
        const text = await res.text();
        let data = null;
        if (text) {
            try {
                data = JSON.parse(text);
            } catch (_ignored) {
                data = { detail: text };
            }
        }
        if (!res.ok) {
            const err = new Error(formatProblemMessage(data, res.status));
            err.status = res.status;
            err.problem = data;
            throw err;
        }
        return data;
    }

    function formatProblemMessage(problem, status) {
        if (!problem || typeof problem !== "object") {
            return `Request failed with HTTP ${status}`;
        }
        const parts = [];
        if (problem.title) {
            parts.push(problem.title);
        }
        if (problem.detail) {
            parts.push(problem.detail);
        }
        if (Array.isArray(problem.reasons) && problem.reasons.length > 0) {
            parts.push(`Reasons: ${problem.reasons.join("; ")}`);
        }
        if (Array.isArray(problem.errors) && problem.errors.length > 0) {
            parts.push(`Validation: ${problem.errors.join("; ")}`);
        }
        return parts.length > 0 ? parts.join(" — ") : `Request failed with HTTP ${status}`;
    }

    async function loadSystemAndHealth() {
        try {
            const [system, health] = await Promise.all([
                fetchJson("/v1/system"),
                fetchJson("/actuator/health").catch(() => ({ status: "DOWN" }))
            ]);
            state.system = system;
            state.health = health;
            renderTopBar();
            renderFilterBar();
            renderSystemTab();
        } catch (err) {
            const healthPill = document.getElementById("health-pill");
            healthPill.textContent = "Health: unreachable";
            healthPill.className = "pill pill-bad";
        }
    }

    function renderTopBar() {
        const healthPill = document.getElementById("health-pill");
        const modelPill = document.getElementById("model-pill");
        const schedulerPill = document.getElementById("scheduler-pill");
        const apiKeyBanner = document.getElementById("api-key-banner");

        const healthStatus = state.health?.status || "UNKNOWN";
        healthPill.textContent = `Health: ${healthStatus}`;
        healthPill.className = `pill ${healthStatus === "UP" ? "pill-ok" : "pill-bad"}`;

        if (state.system) {
            modelPill.textContent = `Model: ${state.system.jevModel}`;
            modelPill.className = `pill ${state.system.jevApiKeyConfigured ? "pill-ok" : "pill-warn"}`;

            const schedText = state.system.schedulerEnabled
                ? `Scheduler: ON (${Math.round(state.system.schedulerFixedDelayMs / 1000)}s)`
                : "Scheduler: OFF";
            schedulerPill.textContent = schedText;
            schedulerPill.className = `pill ${state.system.schedulerEnabled ? "pill-ok" : "pill-muted"}`;

            apiKeyBanner.classList.toggle("hidden", Boolean(state.system.jevApiKeyConfigured));

            const counts = state.system.questionCounts || {};
            const total = Object.values(counts).reduce((acc, val) => acc + Number(val || 0), 0);
            document.getElementById("tab-questions-count").textContent = String(total);
        }
    }

    function renderSystemTab() {
        if (!state.system) {
            return;
        }
        const s = state.system;
        document.getElementById("system-runtime-info").innerHTML = `
            <div class="kv-row"><span class="kv-key">Jev Model</span><span><code>${escapeHtml(s.jevModel)}</code></span></div>
            <div class="kv-row"><span class="kv-key">Jev API Key</span><span>${s.jevApiKeyConfigured ? '<span class="badge badge-RESOLVED_YES">Configured</span>' : '<span class="badge badge-DISPUTED">Missing</span>'}</span></div>
            <div class="kv-row"><span class="kv-key">Jev Base URL</span><span><code>${escapeHtml(s.jevBaseUrl)}</code></span></div>
            <div class="kv-row"><span class="kv-key">GDELT Base URL</span><span><code>${escapeHtml(s.gdeltBaseUrl)}</code></span></div>
            <div class="kv-row"><span class="kv-key">Scheduler Enabled</span><span>${s.schedulerEnabled ? "true" : "false"}</span></div>
            <div class="kv-row"><span class="kv-key">Scheduler Interval</span><span>${formatDurationMs(s.schedulerFixedDelayMs)}</span></div>
            <div class="kv-row"><span class="kv-key">Policy Version</span><span>v${escapeHtml(String(s.policyVersion))}</span></div>
        `;

        const counts = s.questionCounts || {};
        document.getElementById("system-counts-info").innerHTML = STATUS_ORDER.filter((k) => k !== "ALL").map((status) => `
            <div class="metric-box">
                <div class="metric-label">${escapeHtml(status)}</div>
                <div class="metric-value">${Number(counts[status] || 0)}</div>
            </div>
        `).join("");

        const p = s.policy || {};
        const rows = [
            ["minClusterConfirmWeight", p.minClusterConfirmWeight, "Minimum cluster weight to count as an independent confirmation"],
            ["minConfirmClusters", p.minConfirmClusters, "Minimum independent confirming clusters for RESOLVED_YES"],
            ["minTrustedClusters", p.minTrustedClusters, "Minimum tier-1/2 confirming clusters for RESOLVED_YES"],
            ["minConfirmSum", p.minConfirmSum, "Minimum top-5 cluster confirmation sum for RESOLVED_YES"],
            ["maxDenyRatio", p.maxDenyRatio, "Maximum denySum / confirmSum ratio allowed for RESOLVED_YES"],
            ["disputeThreshold", p.disputeThreshold, "Threshold where both confirmSum and denySum trigger DISPUTED"],
            ["postDeadlineDisputeConfirmSum", p.postDeadlineDisputeConfirmSum, "Post-deadline confirmSum that triggers DISPUTED instead of RESOLVED_NO"],
            ["maxFailedScanRatio", p.maxFailedScanRatio, "Maximum GDELT failure ratio before post-deadline UNRESOLVABLE"]
        ];

        document.getElementById("system-policy-info").innerHTML = `
            <table class="data-table">
                <thead>
                    <tr><th>Parameter</th><th>Value</th><th>Meaning</th></tr>
                </thead>
                <tbody>
                    ${rows.map(([name, val, desc]) => `
                        <tr>
                            <td><code>${escapeHtml(name)}</code></td>
                            <td><strong>${escapeHtml(String(val))}</strong></td>
                            <td class="muted">${escapeHtml(desc)}</td>
                        </tr>
                    `).join("")}
                </tbody>
            </table>
        `;
    }

    function initAskForm() {
        const form = document.getElementById("query-form");
        const submitBtn = document.getElementById("query-submit-btn");
        const spinner = document.getElementById("query-spinner");
        const errBox = document.getElementById("query-error");
        const resultBox = document.getElementById("query-result");

        form.addEventListener("submit", async (e) => {
            e.preventDefault();
            errBox.classList.add("hidden");
            resultBox.classList.add("hidden");

            const payload = {
                query: document.getElementById("q-query").value.trim(),
                resolutionCriteria: optionalText("q-criteria"),
                lookbackDays: optionalInt("q-lookback"),
                windowStart: localInputToUtcIso("q-window-start"),
                deadline: localInputToUtcIso("q-deadline"),
                graceHours: optionalInt("q-grace"),
                searchQueries: parseSearchQueries(document.getElementById("q-search-queries").value)
            };

            submitBtn.disabled = true;
            spinner.classList.remove("hidden");
            try {
                const res = await fetchJson("/v1/query", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify(payload)
                });
                renderQueryResult(res);
                loadSystemAndHealth();
                loadQuestions();
            } catch (err) {
                errBox.textContent = err.message;
                errBox.classList.remove("hidden");
                loadSystemAndHealth();
            } finally {
                submitBtn.disabled = false;
                spinner.classList.add("hidden");
            }
        });
    }

    function renderQueryResult(res) {
        const box = document.getElementById("query-result");
        const happenedLabel = res.happened === true ? "YES" : res.happened === false ? "NO" : "UNDECIDED";
        const confPct = typeof res.confidence === "number" ? Math.round(res.confidence * 100) : null;

        box.innerHTML = `
            <div class="row-between">
                <div>
                    <span class="badge badge-${escapeHtml(res.status)}">${escapeHtml(res.status)}</span>
                    <h2 style="margin-top:0.5rem;">${escapeHtml(res.query)}</h2>
                    <p class="muted">Question ID: <code>${escapeHtml(res.questionId)}</code></p>
                </div>
                <button type="button" id="open-in-questions-btn" class="btn btn-secondary btn-sm">Inspect in Questions Tab</button>
            </div>

            <div class="result-summary-grid">
                <div class="metric-box">
                    <div class="metric-label">Happened</div>
                    <div class="metric-value">${escapeHtml(happenedLabel)}</div>
                </div>
                <div class="metric-box">
                    <div class="metric-label">Confidence</div>
                    <div class="metric-value">${confPct !== null ? `${confPct}%` : "N/A"}</div>
                    ${confPct !== null ? `<div class="confidence-bar"><div class="confidence-fill" style="width:${confPct}%"></div></div>` : ""}
                </div>
                <div class="metric-box">
                    <div class="metric-label">Independent Clusters</div>
                    <div class="metric-value">${res.resolution ? escapeHtml(String(res.resolution.independentClusters)) : "0"}</div>
                </div>
                <div class="metric-box">
                    <div class="metric-label">Confirm / Deny Sum</div>
                    <div class="metric-value">${res.resolution ? `${res.resolution.confirmSum.toFixed(2)} / ${res.resolution.denySum.toFixed(2)}` : "--"}</div>
                </div>
            </div>

            <p><strong>Summary:</strong> ${escapeHtml(res.summary || "No summary available.")}</p>

            <div>
                <span class="muted">Compiled GDELT Queries:</span>
                <div class="tag-list">
                    ${(res.searchQueries || []).map((q) => `<span class="tag">${escapeHtml(q)}</span>`).join("") || '<span class="muted">None</span>'}
                </div>
            </div>

            <div class="section-block">
                <h3>Collected Evidence (${(res.evidence || []).length})</h3>
                ${renderEvidenceCards(res.evidence || [])}
            </div>
        `;
        box.classList.remove("hidden");

        document.getElementById("open-in-questions-btn").addEventListener("click", () => {
            state.selectedQuestionId = res.questionId;
            document.querySelector('.tab-btn[data-tab="questions"]').click();
            loadQuestionDetail(res.questionId);
        });
    }

    function initQuestionsPanel() {
        const drawer = document.getElementById("create-question-drawer");
        document.getElementById("toggle-create-btn").addEventListener("click", () => {
            setDefaultQuestionWindow();
            drawer.classList.toggle("hidden");
        });
        document.getElementById("cancel-create-btn").addEventListener("click", () => {
            drawer.classList.add("hidden");
        });
        document.getElementById("refresh-questions-btn").addEventListener("click", () => {
            loadQuestions();
            if (state.selectedQuestionId) {
                loadQuestionDetail(state.selectedQuestionId);
            }
        });

        document.getElementById("create-question-form").addEventListener("submit", async (e) => {
            e.preventDefault();
            const errBox = document.getElementById("create-error");
            const btn = document.getElementById("create-submit-btn");
            errBox.classList.add("hidden");

            const payload = {
                text: document.getElementById("c-text").value.trim(),
                resolutionCriteria: optionalText("c-criteria"),
                windowStart: localInputToUtcIso("c-window-start"),
                deadline: localInputToUtcIso("c-deadline"),
                graceHours: optionalInt("c-grace"),
                searchQueries: parseSearchQueries(document.getElementById("c-search-queries").value),
                webhookUrl: optionalText("c-webhook")
            };

            btn.disabled = true;
            try {
                const created = await fetchJson("/v1/questions", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify(payload)
                });
                drawer.classList.add("hidden");
                document.getElementById("create-question-form").reset();
                setDefaultQuestionWindow();
                state.selectedQuestionId = created.id;
                await Promise.all([loadSystemAndHealth(), loadQuestions()]);
                await loadQuestionDetail(created.id);
            } catch (err) {
                errBox.textContent = err.message;
                errBox.classList.remove("hidden");
                loadSystemAndHealth();
                loadQuestions();
            } finally {
                btn.disabled = false;
            }
        });

        renderFilterBar();
    }

    function setDefaultQuestionWindow() {
        const now = new Date();
        const start = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000);
        const end = new Date(now.getTime() + 7 * 24 * 60 * 60 * 1000);
        const startEl = document.getElementById("c-window-start");
        const endEl = document.getElementById("c-deadline");
        if (startEl && !startEl.value) {
            startEl.value = toUtcDateTimeLocalValue(start);
        }
        if (endEl && !endEl.value) {
            endEl.value = toUtcDateTimeLocalValue(end);
        }
    }

    function renderFilterBar() {
        const bar = document.getElementById("status-filter-bar");
        const counts = state.system?.questionCounts || {};
        const total = Object.values(counts).reduce((acc, v) => acc + Number(v || 0), 0);

        bar.innerHTML = STATUS_ORDER.map((status) => {
            const count = status === "ALL" ? total : Number(counts[status] || 0);
            const active = state.selectedStatusFilter === status ? "active" : "";
            return `<button type="button" class="filter-chip ${active}" data-status="${status}">${status} (${count})</button>`;
        }).join("");

        bar.querySelectorAll(".filter-chip").forEach((chip) => {
            chip.addEventListener("click", () => {
                state.selectedStatusFilter = chip.dataset.status;
                renderFilterBar();
                loadQuestions();
            });
        });
    }

    async function loadQuestions() {
        const errBox = document.getElementById("questions-error");
        errBox.classList.add("hidden");
        const url = state.selectedStatusFilter === "ALL"
            ? "/v1/questions"
            : `/v1/questions?status=${encodeURIComponent(state.selectedStatusFilter)}`;
        try {
            state.questions = await fetchJson(url);
            renderQuestionsTable();
        } catch (err) {
            errBox.textContent = err.message;
            errBox.classList.remove("hidden");
        }
    }

    function renderQuestionsTable() {
        const tbody = document.getElementById("questions-tbody");
        if (!state.questions || state.questions.length === 0) {
            tbody.innerHTML = '<tr><td colspan="5" class="empty-cell">No questions found for this filter.</td></tr>';
            return;
        }

        tbody.innerHTML = state.questions.map((q) => {
            const selected = q.id === state.selectedQuestionId ? "selected" : "";
            return `
                <tr class="question-row ${selected}" data-id="${escapeHtml(q.id)}">
                    <td><span class="badge badge-${escapeHtml(q.status)}">${escapeHtml(q.status)}</span></td>
                    <td>
                        <strong>${escapeHtml(q.text)}</strong>
                        ${q.statusReason ? `<div class="muted">${escapeHtml(q.statusReason)}</div>` : ""}
                    </td>
                    <td class="muted">${formatDateShort(q.windowStart)} → ${formatDateShort(q.deadline)}</td>
                    <td class="muted">${q.lastScanEnd ? formatDateTime(q.lastScanEnd) : "Never"}</td>
                    <td class="muted">${formatDateTime(q.createdAt)}</td>
                </tr>
            `;
        }).join("");

        tbody.querySelectorAll(".question-row").forEach((row) => {
            row.addEventListener("click", () => {
                state.selectedQuestionId = row.dataset.id;
                state.selectedEvidenceStage = "ALL";
                renderQuestionsTable();
                loadQuestionDetail(row.dataset.id);
            });
        });
    }

    async function loadQuestionDetail(id) {
        const card = document.getElementById("question-detail-card");
        card.classList.remove("hidden");
        card.innerHTML = `<p class="muted">Loading details for <code>${escapeHtml(id)}</code>...</p>`;

        const evidenceUrl = state.selectedEvidenceStage === "ALL"
            ? `/v1/questions/${id}/evidence`
            : `/v1/questions/${id}/evidence?stage=${encodeURIComponent(state.selectedEvidenceStage)}`;

        try {
            const [question, evidence, scans, resolution] = await Promise.all([
                fetchJson(`/v1/questions/${id}`),
                fetchJson(evidenceUrl),
                fetchJson(`/v1/questions/${id}/scans`),
                fetchJson(`/v1/questions/${id}/resolution`).catch((err) => (err.status === 404 ? null : Promise.reject(err)))
            ]);

            state.questionDetail = { question, evidence, scans, resolution };
            renderQuestionDetail();
        } catch (err) {
            card.innerHTML = `<div class="alert alert-danger">${escapeHtml(err.message)}</div>`;
        }
    }

    function renderQuestionDetail() {
        const card = document.getElementById("question-detail-card");
        const { question: q, evidence, scans, resolution } = state.questionDetail;
        const canRescan = q.status === "ACTIVE";
        const isDisputed = q.status === "DISPUTED";

        card.innerHTML = `
            <div class="row-between">
                <div>
                    <span class="badge badge-${escapeHtml(q.status)}">${escapeHtml(q.status)}</span>
                    <h2 style="margin-top:0.45rem;">${escapeHtml(q.text)}</h2>
                    <p class="muted">ID: <code>${escapeHtml(q.id)}</code> · Created: ${formatDateTime(q.createdAt)}</p>
                </div>
                <div class="btn-group">
                    ${canRescan ? `<button id="rescan-btn" type="button" class="btn btn-primary">Rescan Now</button>` : ""}
                    <button id="reload-detail-btn" type="button" class="btn btn-secondary">Refresh Detail</button>
                </div>
            </div>

            <div id="detail-action-msg" class="alert alert-danger hidden"></div>

            <div class="result-summary-grid">
                <div class="metric-box">
                    <div class="metric-label">Window Start</div>
                    <div class="metric-value" style="font-size:0.9rem;">${formatDateTime(q.windowStart)}</div>
                </div>
                <div class="metric-box">
                    <div class="metric-label">Deadline (+${q.graceHours}h grace)</div>
                    <div class="metric-value" style="font-size:0.9rem;">${formatDateTime(q.deadline)}</div>
                </div>
                <div class="metric-box">
                    <div class="metric-label">Final Resolution Cutoff</div>
                    <div class="metric-value" style="font-size:0.9rem;">${formatDateTime(q.finalAt)}</div>
                </div>
                <div class="metric-box">
                    <div class="metric-label">Last Scan End</div>
                    <div class="metric-value" style="font-size:0.9rem;">${q.lastScanEnd ? formatDateTime(q.lastScanEnd) : "Not scanned yet"}</div>
                </div>
            </div>

            ${q.resolutionCriteria ? `<p><strong>Criteria:</strong> ${escapeHtml(q.resolutionCriteria)}</p>` : ""}
            ${q.statusReason ? `<p><strong>Status Reason:</strong> ${escapeHtml(q.statusReason)}</p>` : ""}
            ${q.webhookUrl ? `<p><strong>Webhook URL:</strong> <code>${escapeHtml(q.webhookUrl)}</code></p>` : ""}

            <div>
                <span class="muted">Compiled GDELT Queries:</span>
                <div class="tag-list">
                    ${(q.searchQueries || []).map((sq) => `<span class="tag">${escapeHtml(sq)}</span>`).join("") || '<span class="muted">None</span>'}
                </div>
            </div>

            ${isDisputed ? `
                <div class="review-box">
                    <h3>Manual Review Required (DISPUTED)</h3>
                    <p class="muted">Settle this disputed question with an audited outcome.</p>
                    <form id="manual-review-form" class="form-row" style="margin-top:0.75rem; align-items:flex-end;">
                        <div class="form-group flex-1">
                            <label for="review-outcome">Outcome</label>
                            <select id="review-outcome">
                                <option value="RESOLVED_YES">RESOLVED_YES</option>
                                <option value="RESOLVED_NO">RESOLVED_NO</option>
                                <option value="UNRESOLVABLE">UNRESOLVABLE</option>
                            </select>
                        </div>
                        <div class="form-group flex-2">
                            <label for="review-reason">Reason</label>
                            <input id="review-reason" type="text" maxlength="2000" placeholder="Evidence summary or justification" required>
                        </div>
                        <button id="review-submit-btn" type="submit" class="btn btn-warning">Submit Review</button>
                    </form>
                </div>
            ` : ""}

            <div class="section-block">
                <h3>Resolution</h3>
                ${resolution ? `
                    <div class="result-summary-grid">
                        <div class="metric-box"><div class="metric-label">Outcome</div><div class="metric-value">${escapeHtml(resolution.outcome)}</div></div>
                        <div class="metric-box"><div class="metric-label">Confidence</div><div class="metric-value">${Math.round(resolution.confidence * 100)}%</div></div>
                        <div class="metric-box"><div class="metric-label">Confirm / Deny Sum</div><div class="metric-value">${resolution.confirmSum.toFixed(3)} / ${resolution.denySum.toFixed(3)}</div></div>
                        <div class="metric-box"><div class="metric-label">Clusters / Model</div><div class="metric-value">${resolution.independentClusters} (${escapeHtml(resolution.model)})</div></div>
                    </div>
                    <p>${escapeHtml(resolution.details || "")} · Resolved at ${formatDateTime(resolution.resolvedAt)}</p>
                ` : '<p class="muted">Not resolved yet (question is awaiting confirming quorum or deadline).</p>'}
            </div>

            <div class="section-block">
                <div class="row-between">
                    <h3>Evidence &amp; Jev Assessments (${evidence.length})</h3>
                    <div class="filter-bar" style="margin-bottom:0;">
                        ${EVIDENCE_STAGES.map((st) => `
                            <button type="button" class="filter-chip evidence-stage-btn ${state.selectedEvidenceStage === st ? "active" : ""}" data-stage="${st}">${st}</button>
                        `).join("")}
                    </div>
                </div>
                ${renderEvidenceCards(evidence)}
            </div>

            <div class="section-block">
                <h3>Scan History (${scans.length})</h3>
                ${scans.length === 0 ? '<p class="muted">No scans recorded yet.</p>' : `
                    <div class="table-wrap" style="margin-top:0.5rem;">
                        <table class="data-table">
                            <thead>
                                <tr>
                                    <th>Started</th>
                                    <th>Finished</th>
                                    <th>GDELT</th>
                                    <th>Found</th>
                                    <th>New</th>
                                    <th>Error</th>
                                </tr>
                            </thead>
                            <tbody>
                                ${scans.map((s) => `
                                    <tr>
                                        <td>${formatDateTime(s.startedAt)}</td>
                                        <td>${formatDateTime(s.finishedAt)}</td>
                                        <td><span class="badge ${s.gdeltOk ? "badge-RESOLVED_YES" : "badge-FAILED"}">${s.gdeltOk ? "OK" : "ERROR"}</span></td>
                                        <td>${s.articlesFound}</td>
                                        <td>${s.newEvidence}</td>
                                        <td class="muted">${s.error ? escapeHtml(s.error) : "—"}</td>
                                    </tr>
                                `).join("")}
                            </tbody>
                        </table>
                    </div>
                `}
            </div>
        `;

        const rescanBtn = document.getElementById("rescan-btn");
        if (rescanBtn) {
            rescanBtn.addEventListener("click", async () => {
                rescanBtn.disabled = true;
                rescanBtn.textContent = "Scanning...";
                try {
                    await fetchJson(`/v1/questions/${q.id}/rescan`, { method: "POST" });
                    await Promise.all([loadSystemAndHealth(), loadQuestions(), loadQuestionDetail(q.id)]);
                } catch (err) {
                    const msg = document.getElementById("detail-action-msg");
                    msg.textContent = err.message;
                    msg.classList.remove("hidden");
                    rescanBtn.disabled = false;
                    rescanBtn.textContent = "Rescan Now";
                }
            });
        }

        document.getElementById("reload-detail-btn").addEventListener("click", () => {
            loadQuestionDetail(q.id);
        });

        card.querySelectorAll(".evidence-stage-btn").forEach((btn) => {
            btn.addEventListener("click", () => {
                state.selectedEvidenceStage = btn.dataset.stage;
                loadQuestionDetail(q.id);
            });
        });

        const reviewForm = document.getElementById("manual-review-form");
        if (reviewForm) {
            reviewForm.addEventListener("submit", async (e) => {
                e.preventDefault();
                const btn = document.getElementById("review-submit-btn");
                btn.disabled = true;
                try {
                    await fetchJson(`/v1/questions/${q.id}/review`, {
                        method: "POST",
                        headers: { "Content-Type": "application/json" },
                        body: JSON.stringify({
                            outcome: document.getElementById("review-outcome").value,
                            reason: document.getElementById("review-reason").value.trim()
                        })
                    });
                    await Promise.all([loadSystemAndHealth(), loadQuestions(), loadQuestionDetail(q.id)]);
                } catch (err) {
                    const msg = document.getElementById("detail-action-msg");
                    msg.textContent = err.message;
                    msg.classList.remove("hidden");
                    btn.disabled = false;
                }
            });
        }
    }

    function renderEvidenceCards(items) {
        if (!items || items.length === 0) {
            return '<p class="muted" style="margin-top:0.5rem;">No evidence items for this stage.</p>';
        }
        return `<div class="evidence-list">${items.map((ev) => {
            const href = safeHref(ev.url);
            const titleHtml = href
                ? `<a href="${escapeHtml(href)}" target="_blank" rel="noopener noreferrer">${escapeHtml(ev.title || ev.url)}</a>`
                : `<span>${escapeHtml(ev.title || ev.url)}</span>`;
            const a = ev.assessment;
            return `
                <div class="evidence-card">
                    <div class="row-between">
                        <div><strong>${titleHtml}</strong></div>
                        <span class="badge badge-${escapeHtml(ev.stage)}">${escapeHtml(ev.stage)}</span>
                    </div>
                    <div class="evidence-meta">
                        <span>Domain: <code>${escapeHtml(ev.domain)}</code></span>
                        <span>Fetch: <code>${escapeHtml(ev.fetchStatus)}</code></span>
                        ${ev.headlineScore != null ? `<span>Headline: ${ev.headlineScore.toFixed(2)}</span>` : ""}
                        <span>Seen: ${formatDateTime(ev.seenAt)}</span>
                        ${ev.publishedAt ? `<span>Published: ${formatDateTime(ev.publishedAt)}</span>` : ""}
                    </div>
                    ${ev.excerpt ? `<p class="evidence-excerpt">${escapeHtml(ev.excerpt)}</p>` : ""}
                    ${a ? `
                        <div class="assessment-grid">
                            <span><strong>Relevant:</strong> ${a.pRelevant.toFixed(2)}</span>
                            <span><strong>Stance Fits:</strong> ${a.pStanceFits.toFixed(2)}</span>
                            <span><strong>Confirms:</strong> ${a.pConfirms.toFixed(2)}</span>
                            <span><strong>Planned:</strong> ${a.pPlanned.toFixed(2)}</span>
                            <span><strong>Speculation:</strong> ${a.pSpeculation.toFixed(2)}</span>
                            <span><strong>Denies:</strong> ${a.pDenies.toFixed(2)}</span>
                            <span><strong>Fresh:</strong> ${a.pFresh.toFixed(2)}</span>
                            <span><strong>Attribution:</strong> <code>${escapeHtml(a.attribution)}</code></span>
                            <span><strong>Unstable:</strong> ${a.stanceUnstable ? "YES" : "no"} (conf ${a.stanceConfidence.toFixed(2)})</span>
                            ${a.injectionFlag ? '<span class="badge badge-FAILED">INJECTION FLAGGED</span>' : ""}
                        </div>
                    ` : ""}
                </div>
            `;
        }).join("")}</div>`;
    }

    function optionalText(id) {
        const val = document.getElementById(id).value.trim();
        return val.length > 0 ? val : null;
    }

    function optionalInt(id) {
        const raw = document.getElementById(id).value.trim();
        if (!raw) return null;
        const parsed = Number.parseInt(raw, 10);
        return Number.isFinite(parsed) ? parsed : null;
    }

    function localInputToUtcIso(id) {
        const val = document.getElementById(id).value.trim();
        if (!val) return null;
        return val.endsWith("Z") ? val : `${val}:00Z`;
    }

    function toUtcDateTimeLocalValue(date) {
        return date.toISOString().slice(0, 16);
    }

    function parseSearchQueries(raw) {
        if (!raw || !raw.trim()) return null;
        const items = raw
            .split(/[\n,]+/)
            .map((s) => s.trim())
            .filter((s) => s.length > 0);
        return items.length > 0 ? items : null;
    }

    function safeHref(rawUrl) {
        if (!rawUrl || typeof rawUrl !== "string") return null;
        try {
            const parsed = new URL(rawUrl);
            return parsed.protocol === "http:" || parsed.protocol === "https:" ? parsed.href : null;
        } catch (_ignored) {
            return null;
        }
    }

    function formatDateTime(iso) {
        if (!iso) return "—";
        const d = new Date(iso);
        if (Number.isNaN(d.getTime())) return String(iso);
        return d.toISOString().replace("T", " ").slice(0, 19) + " UTC";
    }

    function formatDateShort(iso) {
        if (!iso) return "—";
        const d = new Date(iso);
        if (Number.isNaN(d.getTime())) return String(iso);
        return d.toISOString().slice(0, 10);
    }

    function formatDurationMs(ms) {
        if (!Number.isFinite(ms)) return "—";
        const seconds = Math.round(ms / 1000);
        if (seconds < 60) return `${seconds}s`;
        const minutes = Math.round(seconds / 60);
        return `${minutes}m (${seconds}s)`;
    }

    function escapeHtml(value) {
        return String(value ?? "")
            .replaceAll("&", "&amp;")
            .replaceAll("<", "&lt;")
            .replaceAll(">", "&gt;")
            .replaceAll('"', "&quot;")
            .replaceAll("'", "&#39;");
    }
})();
