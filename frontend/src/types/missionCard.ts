import type { ExecutionPlan } from "./plan";
import type { PlanDetail } from "../utils/summarizePlan";

export type MissionCardStatus = "proposed" | "running";

/** Compact HITL card shown in the command panel (not the full action dump). */
export interface MissionCard {
    planId: string;
    summary: string;
    details: PlanDetail[];
    status: MissionCardStatus;
    plan: ExecutionPlan;
}
