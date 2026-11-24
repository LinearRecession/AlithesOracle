export function optionalText(id) {
    const val = document.getElementById(id).value.trim();
    return val.length > 0 ? val : null;
}

export function optionalInt(id) {
    const raw = document.getElementById(id).value.trim();
    if (!raw) return null;
    const parsed = Number.parseInt(raw, 10);
    return Number.isFinite(parsed) ? parsed : null;
}

export function localInputToUtcIso(id) {
    const val = document.getElementById(id).value.trim();
    if (!val) return null;
    return val.endsWith("Z") ? val : `${val}:00Z`;
}

export function toUtcDateTimeLocalValue(date) {
    return date.toISOString().slice(0, 16);
}

export function parseSearchQueries(raw) {
    if (!raw || !raw.trim()) return null;
    const items = raw
        .split(/[\n,]+/)
        .map((s) => s.trim())
        .filter((s) => s.length > 0);
    return items.length > 0 ? items : null;
}

export function safeHref(rawUrl) {
    if (!rawUrl || typeof rawUrl !== "string") return null;
    try {
        const parsed = new URL(rawUrl);
        return parsed.protocol === "http:" || parsed.protocol === "https:" ? parsed.href : null;
    } catch (_ignored) {
        return null;
    }
}

export function formatDateTime(iso) {
    if (!iso) return "—";
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return String(iso);
    return d.toISOString().replace("T", " ").slice(0, 19) + " UTC";
}

export function formatDateShort(iso) {
    if (!iso) return "—";
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return String(iso);
    return d.toISOString().slice(0, 10);
}

export function formatDurationMs(ms) {
    if (!Number.isFinite(ms)) return "—";
    const seconds = Math.round(ms / 1000);
    if (seconds < 60) return `${seconds}s`;
    const minutes = Math.round(seconds / 60);
    return `${minutes}m (${seconds}s)`;
}

export function escapeHtml(value) {
    return String(value ?? "")
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#39;");
}
