export async function fetchJson(url, options = {}) {
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

export function formatProblemMessage(problem, status) {
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
