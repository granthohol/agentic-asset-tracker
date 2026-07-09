import { useState, type FormEvent } from "react";

import type { MissionCard } from "../types/missionCard";

interface CommandPanelProps {
    planning: boolean;
    executing: boolean;
    stopping: boolean;
    missionCard: MissionCard | null;
    toast: { kind: "ok" | "error"; message: string } | null;
    onSubmit: (command: string) => void;
    onAccept: () => void;
    onReject: () => void;
    onStop: () => void;
}

/**
 * Left-rail HITL surface: command input + compact plan puck (accept/reject/stop).
 */
export default function CommandPanel({
    planning,
    executing,
    stopping,
    missionCard,
    toast,
    onSubmit,
    onAccept,
    onReject,
    onStop,
}: CommandPanelProps) {
    const [command, setCommand] = useState("");
    const missionRunning = missionCard?.status === "running";
    const busy = planning || executing || stopping || missionRunning;

    const submit = (e: FormEvent) => {
        e.preventDefault();
        const trimmed = command.trim();
        if (!trimmed || busy) return;
        setCommand("");
        onSubmit(trimmed);
    };

    return (
        <aside className="command-panel">
            <header className="command-panel__header">
                <h1 className="command-panel__title">Command</h1>
                <p className="command-panel__subtitle">Plan with the agent, approve before execute.</p>
            </header>

            <form className="command-panel__form" onSubmit={submit}>
                <textarea
                    className="command-panel__input"
                    rows={3}
                    placeholder='e.g. "Observe the disturbance at 39.05,-77.18"'
                    value={command}
                    onChange={(e) => setCommand(e.target.value)}
                    disabled={busy}
                />
                <button
                    className="command-panel__plan-btn"
                    type="submit"
                    disabled={busy || !command.trim()}
                >
                    {planning ? "Planning…" : "Plan"}
                </button>
            </form>

            <div className="command-panel__body">
                {planning && !missionCard && (
                    <p className="command-panel__status">Running planner tools…</p>
                )}

                {!planning && !missionCard && (
                    <p className="command-panel__status">
                        Type a command above. Proposed plans appear here as a compact card.
                    </p>
                )}

                {missionCard && (
                    <section className="command-panel__puck">
                        <div className="command-panel__puck-top">
                            <p className="command-panel__puck-summary">{missionCard.summary}</p>
                            <span
                                className={`command-panel__badge command-panel__badge--${missionCard.status}`}
                            >
                                {missionCard.status === "proposed" ? "Proposed" : "Running"}
                            </span>
                        </div>
                        {missionCard.details.length > 0 && (
                            <dl className="command-panel__puck-details">
                                {missionCard.details.map((d) => (
                                    <div key={d.label} className="command-panel__puck-row">
                                        <dt>{d.label}</dt>
                                        <dd>{d.value}</dd>
                                    </div>
                                ))}
                            </dl>
                        )}
                        <div className="command-panel__buttons">
                            {missionCard.status === "proposed" && (
                                <>
                                    <button
                                        type="button"
                                        className="btn btn--reject"
                                        onClick={onReject}
                                        disabled={executing}
                                    >
                                        Reject
                                    </button>
                                    <button
                                        type="button"
                                        className="btn btn--approve"
                                        onClick={onAccept}
                                        disabled={executing}
                                    >
                                        {executing ? "Dispatching…" : "Accept"}
                                    </button>
                                </>
                            )}
                            {missionCard.status === "running" && (
                                <button
                                    type="button"
                                    className="btn btn--stop"
                                    onClick={onStop}
                                    disabled={stopping}
                                >
                                    {stopping ? "Stopping…" : "Stop"}
                                </button>
                            )}
                        </div>
                    </section>
                )}
            </div>

            {toast && (
                <div className={`command-panel__toast command-panel__toast--${toast.kind}`}>
                    {toast.message}
                </div>
            )}
        </aside>
    );
}
