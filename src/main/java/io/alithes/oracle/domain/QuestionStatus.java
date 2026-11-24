package io.alithes.oracle.domain;

public enum QuestionStatus {
    ACTIVE,
    REJECTED,
    RESOLVED_YES,
    RESOLVED_NO,
    DISPUTED,
    UNRESOLVABLE;

    public boolean isFinal() {
        return this != ACTIVE;
    }
}
