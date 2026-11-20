"use strict";

const byId = (id) => document.getElementById(id);
const word = byId("word");
const gameFeedback = byId("game-feedback");

let game;
let lastAward;
let gameBusy = false;
let polling = false;

function showFeedback(message, isError = false) {
    gameFeedback.textContent = message;
    gameFeedback.classList.toggle("error", isError);
}

async function request(path, options = {}) {
    const response = await fetch("/game-test/api" + path, { cache: "no-store", ...options });
    if (!response.ok) {
        const problem = await response.json().catch(() => null);
        const error = new Error(problem?.detail || `Request failed (HTTP ${response.status}).`);
        error.status = response.status;
        throw error;
    }
    return response.json();
}

function gamePath() {
    return `/game/${game.gameId}`;
}

function updateControls() {
    byId("start-game").disabled = gameBusy;
    byId("complete-letter").disabled = !game || game.state.finished || gameBusy;
}

function showWord(view) {
    const { word: currentWord, letterIndex, finished } = view.state;
    const letters = Array.from(currentWord, (letter, index) => {
        const tile = document.createElement("span");
        tile.classList.add("letter");
        tile.classList.toggle("completed", index < letterIndex);
        tile.classList.toggle("active", index === letterIndex);
        tile.setAttribute("aria-hidden", "true");
        tile.textContent = letter;
        return tile;
    });
    word.setAttribute("role", "img");
    word.setAttribute("aria-label",
        `${currentWord}. ${finished ? "Final" : "Active"} letter ${view.expectedLetter}.`);
    word.replaceChildren(...letters);
}

function showGame(view) {
    const { state } = view;
    if (game?.gameId !== view.gameId || game.state.positionId !== state.positionId
        || game.state.finished !== state.finished) {
        showWord(view);
    }
    const status = state.finished ? "Finished" : "In progress";
    const gameStatus = byId("game-status");
    if (gameStatus.textContent !== status) {
        gameStatus.textContent = status;
    }
    game = view;
    byId("score").textContent = state.score;
    byId("score-change").textContent = lastAward === undefined ? "—" : `+${lastAward}`;
    byId("time-left").textContent = `${(Math.ceil(state.remainingMs / 100) / 10).toFixed(1)} s`;
    byId("position-id").textContent = state.positionId;
    byId("repeated-letter").textContent = state.repeatsPreviousLetter ? "Yes" : "No";
    updateControls();
}

function showGameError(error) {
    if (error.status === 404 && game) {
        game = undefined;
        byId("game-status").textContent = "Game unavailable";
    }
    showFeedback(error.message, true);
    updateControls();
}

async function gameAction(action) {
    if (gameBusy) return;
    gameBusy = true;
    updateControls();
    showFeedback("");
    try {
        await action();
    } catch (error) {
        showGameError(error);
    } finally {
        gameBusy = false;
        updateControls();
    }
}

byId("start-game").addEventListener("click", () => {
    gameAction(async () => {
        const view = await request("/game", { method: "POST" });
        lastAward = undefined;
        byId("start-game").textContent = "New game";
        showGame(view);
    });
});

byId("complete-letter").addEventListener("click", () => {
    gameAction(async () => {
        const positionId = game.state.positionId;
        const attempt = await request(gamePath() + "/complete", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ positionId }),
        });
        if (attempt.accepted) {
            lastAward = attempt.scoreChange;
        }
        showGame(attempt.game);
        if (!attempt.accepted) {
            showFeedback(`Position ${positionId} rejected.`);
        }
    });
});

async function refreshClock() {
    if (gameBusy || polling || !game || game.state.finished) return;
    const viewBeforeRequest = game;
    polling = true;
    try {
        const view = await request(gamePath());
        if (game === viewBeforeRequest && !gameBusy) {
            showGame(view);
        }
    } catch (error) {
        if (game === viewBeforeRequest && !gameBusy) {
            showGameError(error);
        }
    } finally {
        polling = false;
    }
}

setInterval(refreshClock, 250);
