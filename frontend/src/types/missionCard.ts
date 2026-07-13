import type { ExecutionPlan } from "./plan";
import type { PlanDetail } from "../utils/summarizePlan";

export type MissionCardStatus = "proposed" | "running";

/** Compact plan card in the command panel. */
export interface MissionCard {
    planId: string;
    summary: string;
    details: PlanDetail[];
    status: MissionCardStatus;
    plan: ExecutionPlan;
}
