import { useCallback, useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react'

import DroneMap from './components/DroneMap'
import CommandPanel from './components/CommandPanel'
import { requestPlan, executePlan, cancelMission } from './api'
import type { ExecutionPlan } from './types/plan'
import type { AcceptedRoute } from './types/route'
import type { MissionObjective } from './types/missionObjective'
import type { MissionCard } from './types/missionCard'
import { summarizePlan } from './utils/summarizePlan'
import './App.css'

interface Toast {
  kind: 'ok' | 'error';
  message: string;
}

const PANEL_PCT_DEFAULT = 28;
const PANEL_PCT_MIN = 18;
const PANEL_PCT_MAX = 55;

function clampPanelPct(pct: number): number {
  return Math.min(PANEL_PCT_MAX, Math.max(PANEL_PCT_MIN, pct));
}

function isFormUpMission(missionType?: string): boolean {
  const m = (missionType ?? '').toUpperCase();
  return m === 'FORM_UP' || m === 'HOLD';
}

function routesFromPlan(plan: ExecutionPlan, missionFilter?: 'FORM_UP' | 'ADVANCE'): AcceptedRoute[] {
  return plan.actions.flatMap((action, i) => {
    if (action.op !== 'setWaypoint') return [];
    const formUp = isFormUpMission(action.mission_type);
    if (missionFilter === 'FORM_UP' && !formUp) return [];
    if (missionFilter === 'ADVANCE' && formUp) return [];
    return [{
      id: `${plan.planId}-${i}`,
      droneId: action.droneId,
      targetLat: action.targetLat,
      targetLng: action.targetLng,
      missionType: action.mission_type,
    }];
  });
}

function objectivesFromPlan(plan: ExecutionPlan): MissionObjective[] {
  return plan.actions.flatMap((action, i) => {
    if (action.op !== 'upsertObjective') return [];
    if (action.centerLatitude == null || action.centerLongitude == null) return [];
    return [{
      id: `${plan.planId}-obj-${i}`,
      name: action.name,
      centerLatitude: action.centerLatitude,
      centerLongitude: action.centerLongitude,
      radiusMeters: action.radiusMeters ?? 300,
    }];
  });
}

function droneIdsFromPlan(plan: ExecutionPlan): string[] {
  const ids = new Set<string>();
  for (const a of plan.actions) {
    if (a.op === 'setWaypoint') {
      ids.add(a.droneId);
    }
  }
  return [...ids];
}

function App() {
  const [pendingPlan, setPendingPlan] = useState<ExecutionPlan | null>(null);
  const [acceptedRoutes, setAcceptedRoutes] = useState<AcceptedRoute[]>([]);
  /** Disturbance / AOI circles — survive approve until the mission routes finish. */
  const [activeObjectives, setActiveObjectives] = useState<MissionObjective[]>([]);
  const [missionCard, setMissionCard] = useState<MissionCard | null>(null);
  const [planning, setPlanning] = useState(false);
  const [executing, setExecuting] = useState(false);
  const [stopping, setStopping] = useState(false);
  const [toast, setToast] = useState<Toast | null>(null);
  const [panelPct, setPanelPct] = useState(PANEL_PCT_DEFAULT);
  const [resizing, setResizing] = useState(false);
  const appRef = useRef<HTMLDivElement>(null);
  /** Full approved plan kept so we can swap FORM_UP overlays → ADVANCE after form-up. */
  const approvedPlanRef = useRef<ExecutionPlan | null>(null);

  const flash = (t: Toast) => {
    setToast(t);
    window.setTimeout(() => setToast(null), 5000);
  };

  const clearMissionUi = () => {
    approvedPlanRef.current = null;
    setAcceptedRoutes([]);
    setActiveObjectives([]);
    setPendingPlan(null);
    setMissionCard(null);
  };

  const handlePlan = async (command: string) => {
    setPlanning(true);
    setPendingPlan(null);
    setMissionCard(null);
    try {
      const plan = await requestPlan(command);
      const { summary, details } = summarizePlan(plan);
      setPendingPlan(plan);
      setMissionCard({
        planId: plan.planId,
        summary,
        details,
        status: 'proposed',
        plan,
      });
    } catch (err) {
      flash({ kind: 'error', message: String(err) });
    } finally {
      setPlanning(false);
    }
  };

  const handleAccept = async () => {
    if (!missionCard || missionCard.status !== 'proposed') return;
    const plan = missionCard.plan;
    setExecuting(true);
    try {
      const result = await executePlan(plan);
      approvedPlanRef.current = plan;
      const formUp = routesFromPlan(plan, 'FORM_UP');
      const advanceOnly = routesFromPlan(plan, 'ADVANCE');
      setAcceptedRoutes(formUp.length > 0 ? formUp : [...formUp, ...advanceOnly]);
      setActiveObjectives(objectivesFromPlan(plan));
      setPendingPlan(null);
      setMissionCard((prev) => (prev ? { ...prev, status: 'running' } : null));
      flash({ kind: 'ok', message: `Plan ${result.planId} accepted for execution` });
    } catch (err) {
      flash({ kind: 'error', message: String(err) });
    } finally {
      setExecuting(false);
    }
  };

  const handleReject = () => {
    setPendingPlan(null);
    setMissionCard(null);
  };

  const handleStop = async () => {
    if (!missionCard || missionCard.status !== 'running') return;
    const fromRoutes = acceptedRoutes.map((r) => r.droneId);
    const fromPlan = droneIdsFromPlan(missionCard.plan);
    const droneIds = [...new Set([...fromRoutes, ...fromPlan])];
    setStopping(true);
    try {
      if (droneIds.length > 0) {
        await cancelMission(droneIds);
      }
      clearMissionUi();
      flash({ kind: 'ok', message: 'Mission stopped' });
    } catch (err) {
      flash({ kind: 'error', message: String(err) });
    } finally {
      setStopping(false);
    }
  };

  const handleRoutesCompleted = useCallback((completedIds: string[]) => {
    if (completedIds.length === 0) return;
    const done = new Set(completedIds);

    setAcceptedRoutes((prev) => {
      const remaining = prev.filter((route) => !done.has(route.id));
      const completedFormUp = prev.some(
        (r) => done.has(r.id) && isFormUpMission(r.missionType),
      );
      const stillHasFormUp = remaining.some((r) => isFormUpMission(r.missionType));

      if (completedFormUp && !stillHasFormUp && approvedPlanRef.current) {
        const advance = routesFromPlan(approvedPlanRef.current, 'ADVANCE');
        if (advance.length > 0) {
          return advance;
        }
      }
      return remaining;
    });
  }, []);

  // Drop AOI + puck when the mission has no more live routes.
  useEffect(() => {
    if (
      acceptedRoutes.length === 0
      && !pendingPlan
      && missionCard?.status === 'running'
    ) {
      approvedPlanRef.current = null;
      setActiveObjectives([]);
      setMissionCard(null);
    }
  }, [acceptedRoutes.length, pendingPlan, missionCard?.status]);

  const onResizePointerDown = useCallback((e: ReactPointerEvent<HTMLDivElement>) => {
    e.preventDefault();
    const handle = e.currentTarget;
    handle.setPointerCapture(e.pointerId);
    setResizing(true);
  }, []);

  useEffect(() => {
    if (!resizing) return;

    const onMove = (e: PointerEvent) => {
      const app = appRef.current;
      if (!app) return;
      const rect = app.getBoundingClientRect();
      if (rect.width <= 0) return;
      const pct = ((e.clientX - rect.left) / rect.width) * 100;
      setPanelPct(clampPanelPct(pct));
    };

    const onUp = () => setResizing(false);

    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp);
    window.addEventListener('pointercancel', onUp);
    return () => {
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('pointerup', onUp);
      window.removeEventListener('pointercancel', onUp);
    };
  }, [resizing]);

  return (
    <div
      ref={appRef}
      className={`app${resizing ? ' app--resizing' : ''}`}
    >
      <div className="app__panel" style={{ width: `${panelPct}%` }}>
        <CommandPanel
          planning={planning}
          executing={executing}
          stopping={stopping}
          missionCard={missionCard}
          toast={toast}
          onSubmit={handlePlan}
          onAccept={handleAccept}
          onReject={handleReject}
          onStop={handleStop}
        />
      </div>

      <div
        className="app__resizer"
        role="separator"
        aria-orientation="vertical"
        aria-label="Resize command panel"
        aria-valuemin={PANEL_PCT_MIN}
        aria-valuemax={PANEL_PCT_MAX}
        aria-valuenow={Math.round(panelPct)}
        onPointerDown={onResizePointerDown}
      />

      <div className="app__map">
        <DroneMap
          pendingPlan={pendingPlan}
          acceptedRoutes={acceptedRoutes}
          activeObjectives={activeObjectives}
          onRoutesCompleted={handleRoutesCompleted}
        />
      </div>
    </div>
  );
}

export default App
