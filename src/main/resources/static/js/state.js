export const STATUS_ORDER = [
    "ALL",
    "ACTIVE",
    "RESOLVED_YES",
    "RESOLVED_NO",
    "DISPUTED",
    "REJECTED",
    "UNRESOLVABLE"
];

export const EVIDENCE_STAGES = ["ALL", "ASSESSED", "SKIPPED", "FAILED", "NEW"];

export const state = {
    system: null,
    health: null,
    questions: [],
    selectedStatusFilter: "ALL",
    selectedQuestionId: null,
    selectedEvidenceStage: "ALL",
    questionDetail: null
};
