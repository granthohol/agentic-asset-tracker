import { useCallback, useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react'

import DroneMap from './components/DroneMap'
import CommandPanel from './components/CommandPanel'
import { requestPlan, executePlan } from './api'
import type { ExecutionPlan } from './types/plan'
import type { AcceptedRoute } from './types/route'
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

function App() {
  const [pendingPlan, setPendingPlan] = useState<ExecutionPlan | null>(null);
  const [acceptedRoutes, setAcceptedRoutes] = useState<AcceptedRoute[]>([]);
  const [planning, setPlanning] = useState(false);
  const [executing, setExecuting] = useState(false);
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

  const handlePlan = async (command: string) => {
    setPlanning(true);
    setPendingPlan(null);
    try {
      const plan = await requestPlan(command);
      setPendingPlan(plan);
    } catch (err) {
      flash({ kind: 'error', message: String(err) });
    } finally {
      setPlanning(false);
    }
  };

  const handleApprove = async () => {
    if (!pendingPlan) return;
    setExecuting(true);
    try {
      const result = await executePlan(pendingPlan);
      approvedPlanRef.current = pendingPlan;
      const formUp = routesFromPlan(pendingPlan, 'FORM_UP');
      const advanceOnly = routesFromPlan(pendingPlan, 'ADVANCE');
      // If the plan has no FORM_UP wave, show all waypoints immediately.
      setAcceptedRoutes(formUp.length > 0 ? formUp : [...formUp, ...advanceOnly]);
      setPendingPlan(null);
      flash({ kind: 'ok', message: `Plan ${result.planId} accepted for execution` });
    } catch (err) {
      flash({ kind: 'error', message: String(err) });
    } finally {
      setExecuting(false);
    }
  };

  const handleReject = () => setPendingPlan(null);

  const handleRoutesCompleted = useCallback((completedIds: string[]) => {
    if (completedIds.length === 0) return;
    const done = new Set(completedIds);

    setAcceptedRoutes((prev) => {
      const remaining = prev.filter((route) => !done.has(route.id));
      const completedFormUp = prev.some(
        (r) => done.has(r.id) && isFormUpMission(r.missionType),
      );
      const stillHasFormUp = remaining.some((r) => isFormUpMission(r.missionType));

      // After the last FORM_UP overlay clears, swap in ADVANCE routes from the approved plan.
      if (completedFormUp && !stillHasFormUp && approvedPlanRef.current) {
        const advance = routesFromPlan(approvedPlanRef.current, 'ADVANCE');
        if (advance.length > 0) {
          return advance;
        }
      }
      return remaining;
    });
  }, []);

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
          pendingPlan={pendingPlan}
          toast={toast}
          onSubmit={handlePlan}
          onApprove={handleApprove}
          onReject={handleReject}
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
          onRoutesCompleted={handleRoutesCompleted}
        />
      </div>
    </div>
  );
}

export default App
