import { fetchJson } from "./api.js";
import { renderEvidenceCards } from "./evidenceView.js";
import { escapeHtml, localInputToUtcIso, optionalInt, optionalText, parseSearchQueries } from "./formatters.js";

export function initAskForm({ onQueryComplete, onQueryError, onInspectQuestion }) {
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
            renderQueryResult(res, onInspectQuestion);
            onQueryComplete?.();
        } catch (err) {
            errBox.textContent = err.message;
            errBox.classList.remove("hidden");
            onQueryError?.();
        } finally {
            submitBtn.disabled = false;
            spinner.classList.add("hidden");
        }
    });
}

function renderQueryResult(res, onInspectQuestion) {
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
        onInspectQuestion?.(res.questionId);
    });
}
