import { useState, type FormEvent } from "react";

interface PromptBarProps {
    loading: boolean;
    onSubmit: (command: string) => void;
    disabled?: boolean;
}

/**
 * Floating command input over the map. Submitting POSTs to /api/plan (handled by the
 * parent) and shows a loading state while the planner runs its tool loop.
 */
export default function PromptBar({ loading, onSubmit, disabled }: PromptBarProps) {
    const [command, setCommand] = useState("");

    const submit = (e: FormEvent) => {
        e.preventDefault();
        const trimmed = command.trim();
        if (!trimmed || loading || disabled) return;
        onSubmit(trimmed);
    };

    return (
        <form className="prompt-bar" onSubmit={submit}>
            <input
                className="prompt-bar__input"
                type="text"
                placeholder='Command the fleet — e.g. "Observe the disturbance at 39.05,-77.18"'
                value={command}
                onChange={(e) => setCommand(e.target.value)}
                disabled={loading || disabled}
            />
            <button className="prompt-bar__button" type="submit" disabled={loading || disabled || !command.trim()}>
                {loading ? "Planning…" : "Plan"}
            </button>
        </form>
    );
}
