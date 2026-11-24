import { escapeHtml, formatDateTime, safeHref } from "./formatters.js";

export function renderEvidenceCards(items) {
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
