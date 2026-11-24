import { initAskForm } from "./js/askTab.js";
import {
    initQuestionsPanel,
    loadQuestions,
    openQuestionInTab,
    renderFilterBar,
    setDefaultQuestionWindow
} from "./js/questionsTab.js";
import { loadSystemAndHealth, setSystemUpdatedHandler } from "./js/systemTab.js";

document.addEventListener("DOMContentLoaded", () => {
    setSystemUpdatedHandler(renderFilterBar);
    initTabs();
    initAskForm({
        onQueryComplete: () => {
            loadSystemAndHealth();
            loadQuestions();
        },
        onQueryError: () => {
            loadSystemAndHealth();
        },
        onInspectQuestion: (questionId) => {
            openQuestionInTab(questionId);
        }
    });
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
