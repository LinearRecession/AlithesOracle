import { fetchJson } from "./api.js";
import { renderEvidenceCards } from "./evidenceView.js";
import {
    escapeHtml,
    formatDateShort,
    formatDateTime,
    localInputToUtcIso,
    optionalInt,
    optionalText,
    parseSearchQueries,
    toUtcDateTimeLocalValue
} from "./formatters.js";
import { EVIDENCE_STAGES, state, STATUS_ORDER } from "./state.js";
import { loadSystemAndHealth } from "./systemTab.js";

export function initQuestionsPanel() {
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

export function setDefaultQuestionWindow() {
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

export function renderFilterBar() {
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

export async function loadQuestions() {
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

export function openQuestionInTab(questionId) {
    state.selectedQuestionId = questionId;
    document.querySelector('.tab-btn[data-tab="questions"]').click();
    loadQuestionDetail(questionId);
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

export async function loadQuestionDetail(id) {
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
