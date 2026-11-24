import { fetchJson } from "./api.js";
import { escapeHtml, formatDurationMs } from "./formatters.js";
import { state, STATUS_ORDER } from "./state.js";

let onSystemUpdated = () => {};

export function setSystemUpdatedHandler(handler) {
    onSystemUpdated = typeof handler === "function" ? handler : () => {};
}

export async function loadSystemAndHealth() {
    try {
        const [system, health] = await Promise.all([
            fetchJson("/v1/system"),
            fetchJson("/actuator/health").catch(() => ({ status: "DOWN" }))
        ]);
        state.system = system;
        state.health = health;
        renderTopBar();
        onSystemUpdated();
        renderSystemTab();
    } catch (_err) {
        const healthPill = document.getElementById("health-pill");
        healthPill.textContent = "Health: unreachable";
        healthPill.className = "pill pill-bad";
    }
}

export function renderTopBar() {
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

export function renderSystemTab() {
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
