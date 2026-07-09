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

function headerStatus(
    planning: boolean,
    executing: boolean,
    stopping: boolean,
    missionCard: MissionCard | null,
): string {
    if (stopping) return "Aborting";
    if (planning) return "Planning";
    if (executing) return "Dispatching";
    if (missionCard?.status === "running") return "Executing";
    if (missionCard?.status === "proposed") return "Awaiting approval";
    return "Agent ready";
}

/**
 * Left-rail C2 plan console: create plans, review ticket, accept / abort.
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
    const status = headerStatus(planning, executing, stopping, missionCard);
    const fault = toast?.kind === "error" ? toast.message : null;

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
                <div className="command-panel__brand">
                    <span className="command-panel__mark">C2</span>
                    <h1 className="command-panel__title">Plans</h1>
                </div>
                <p className="command-panel__phase">{status}</p>
            </header>

            <form className="command-panel__form" onSubmit={submit}>
                <label className="command-panel__field-label" htmlFor="plan-command">
                    Command
                </label>
                <textarea
                    id="plan-command"
                    className="command-panel__input"
                    rows={2}
                    placeholder="Agent ready. Plan a mission."
                    value={command}
                    onChange={(e) => setCommand(e.target.value)}
                    disabled={busy}
                />
                <button
                    className="command-panel__issue"
                    type="submit"
                    disabled={busy || !command.trim()}
                >
                    {planning ? "Planning…" : "Create Plan"}
                </button>
            </form>

            <div className="command-panel__body">
                {!missionCard && !planning && (
                    <p className="command-panel__idle">No Active Plans</p>
                )}

                {missionCard && (
                    <section
                        className={`command-panel__ticket command-panel__ticket--${missionCard.status}`}
                    >
                        <div className="command-panel__ticket-head">
                            <p className="command-panel__ticket-title">{missionCard.summary}</p>
                            <span className="command-panel__ticket-status">
                                {missionCard.status === "proposed" ? "Proposed" : "Running"}
                            </span>
                        </div>

                        {missionCard.details.length > 0 && (
                            <dl className="command-panel__ticket-details">
                                {missionCard.details.map((d) => (
                                    <div key={d.label} className="command-panel__ticket-row">
                                        <dt>{d.label}</dt>
                                        <dd>{d.value}</dd>
                                    </div>
                                ))}
                            </dl>
                        )}

                        <div className="command-panel__actions">
                            {missionCard.status === "proposed" && (
                                <>
                                    <button
                                        type="button"
                                        className="c2-btn c2-btn--ghost"
                                        onClick={onReject}
                                        disabled={executing}
                                    >
                                        Reject
                                    </button>
                                    <button
                                        type="button"
                                        className="c2-btn c2-btn--accept"
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
                                    className="c2-btn c2-btn--abort"
                                    onClick={onStop}
                                    disabled={stopping}
                                >
                                    {stopping ? "Aborting…" : "Abort"}
                                </button>
                            )}
                        </div>
                    </section>
                )}
            </div>

            {fault && (
                <div className="command-panel__fault" role="alert">
                    {fault}
                </div>
            )}
        </aside>
    );
}
