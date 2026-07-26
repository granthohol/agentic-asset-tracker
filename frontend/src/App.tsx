import { useCallback, useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react'

import DroneMap from './components/DroneMap'
import CommandPanel from './components/CommandPanel'
import EntityToolbar from './components/EntityToolbar'
import { requestPlan, executePlan, cancelMission } from './api'
import type { ExecutionPlan } from './types/plan'
import type { AcceptedRoute } from './types/route'
import type { MissionObjective } from './types/missionObjective'
import type { MissionCard } from './types/missionCard'
import { summarizePlan } from './utils/summarizePlan'
import { useEntityFeed } from './hooks/useEntityFeed'
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

function normalizeMission(missionType?: string): string {
  return (missionType ?? '').trim().toUpperCase();
}

/**
 * Ordered motion waves for overlays. Contiguous setWaypoints share a wave by mission_type;
 * each setRoute leg is its own one-drone wave (TRANSIT then final mission_type).
 */
function routeWavesFromPlan(plan: ExecutionPlan): AcceptedRoute[][] {
  const waves: AcceptedRoute[][] = [];
  const actions = plan.actions;
  let i = 0;
  while (i < actions.length) {
    const action = actions[i];
    if (action.op === 'setRoute') {
      const legs = action.legs ?? [];
      for (let li = 0; li < legs.length; li++) {
        const [lat, lng] = legs[li];
        const last = li === legs.length - 1;
        waves.push([{
          id: `${plan.planId}-route-${i}-leg-${li}`,
          droneId: action.droneId,
          targetLat: lat,
          targetLng: lng,
          missionType: last ? (action.mission_type ?? 'ADVANCE') : 'TRANSIT',
        }]);
      }
      i++;
      continue;
    }
    if (action.op === 'setWaypoint') {
      const waveType = normalizeMission(action.mission_type);
      const wave: AcceptedRoute[] = [];
      while (i < actions.length) {
        const next = actions[i];
        if (next.op !== 'setWaypoint' || normalizeMission(next.mission_type) !== waveType) {
          break;
        }
        wave.push({
          id: `${plan.planId}-${i}`,
          droneId: next.droneId,
          targetLat: next.targetLat,
          targetLng: next.targetLng,
          missionType: next.mission_type,
        });
        i++;
      }
      if (wave.length > 0) {
        waves.push(wave);
      }
      continue;
    }
    i++;
  }
  return waves;
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
    if (a.op === 'setWaypoint' || a.op === 'setRoute') {
      ids.add(a.droneId);
    }
  }
  return [...ids];
}

function App() {
  // Entity feed (tracks/waypoints/zones). Mount once.
  useEntityFeed();

  const [pendingPlan, setPendingPlan] = useState<ExecutionPlan | null>(null);
  const [acceptedRoutes, setAcceptedRoutes] = useState<AcceptedRoute[]>([]);
  /** AOI circles stick around until the mission routes finish. */
  const [activeObjectives, setActiveObjectives] = useState<MissionObjective[]>([]);
  const [missionCard, setMissionCard] = useState<MissionCard | null>(null);
  const [planning, setPlanning] = useState(false);
  const [executing, setExecuting] = useState(false);
  const [stopping, setStopping] = useState(false);
  const [toast, setToast] = useState<Toast | null>(null);
  const [panelPct, setPanelPct] = useState(PANEL_PCT_DEFAULT);
  const [resizing, setResizing] = useState(false);
  const appRef = useRef<HTMLDivElement>(null);
  /** Kept so we can clear drones when the last wave finishes. */
  const approvedPlanRef = useRef<ExecutionPlan | null>(null);
  /** Remaining overlay waves after the current acceptedRoutes wave. */
  const remainingWavesRef = useRef<AcceptedRoute[][]>([]);

  const flash = (t: Toast) => {
    setToast(t);
    window.setTimeout(() => setToast(null), 5000);
  };

  const clearMissionUi = () => {
    approvedPlanRef.current = null;
    remainingWavesRef.current = [];
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
      await executePlan(plan);
      approvedPlanRef.current = plan;
      const waves = routeWavesFromPlan(plan);
      remainingWavesRef.current = waves.slice(1);
      setAcceptedRoutes(waves[0] ?? []);
      setActiveObjectives(objectivesFromPlan(plan));
      setPendingPlan(null);
      setMissionCard((prev) => (prev ? { ...prev, status: 'running' } : null));
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
      // Whole current wave done → swap in the next wave (HOLD / TRANSIT / ADVANCE / …).
      if (remaining.length === 0 && remainingWavesRef.current.length > 0) {
        const [next, ...rest] = remainingWavesRef.current;
        remainingWavesRef.current = rest;
        return next;
      }
      return remaining;
    });
  }, []);

  // Mission done: clear AOI + puck, send CLEAR_WAYPOINT so drones roam again.
  useEffect(() => {
    if (
      acceptedRoutes.length === 0
      && remainingWavesRef.current.length === 0
      && !pendingPlan
      && missionCard?.status === 'running'
    ) {
      const finishedPlan = approvedPlanRef.current;
      approvedPlanRef.current = null;
      setActiveObjectives([]);
      setMissionCard(null);
      if (finishedPlan) {
        const droneIds = droneIdsFromPlan(finishedPlan);
        if (droneIds.length > 0) {
          // Best effort. Failure just leaves them holding station.
          void cancelMission(droneIds).catch(() => {});
        }
      }
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
        <EntityToolbar />
      </div>
    </div>
  );
}

export default App
