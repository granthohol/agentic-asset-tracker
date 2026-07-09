import { useState } from 'react'

import DroneMap from './components/DroneMap'
import PromptBar from './components/PromptBar'
import PlanModal from './components/PlanModal'
import { requestPlan, executePlan } from './api'
import type { ExecutionPlan } from './types/plan'
import './App.css'

interface Toast {
  kind: 'ok' | 'error';
  message: string;
}

function App() {
  // The proposed plan currently awaiting approval. Drives both the modal and the
  // map's ghost overlay. null = nothing pending.
  const [activePlan, setActivePlan] = useState<ExecutionPlan | null>(null);
  const [planning, setPlanning] = useState(false);
  const [executing, setExecuting] = useState(false);
  const [toast, setToast] = useState<Toast | null>(null);

  const flash = (t: Toast) => {
    setToast(t);
    window.setTimeout(() => setToast(null), 5000);
  };

  const handlePlan = async (command: string) => {
    setPlanning(true);
    setActivePlan(null);
    try {
      const plan = await requestPlan(command);
      setActivePlan(plan);
    } catch (err) {
      flash({ kind: 'error', message: String(err) });
    } finally {
      setPlanning(false);
    }
  };

  const handleApprove = async () => {
    if (!activePlan) return;
    setExecuting(true);
    try {
      const result = await executePlan(activePlan);
      flash({ kind: 'ok', message: `Plan ${result.planId} accepted for execution` });
      setActivePlan(null);
    } catch (err) {
      flash({ kind: 'error', message: String(err) });
    } finally {
      setExecuting(false);
    }
  };

  const handleReject = () => setActivePlan(null);

  return (
    <div className="app">
      <DroneMap activePlan={activePlan} />

      <PromptBar loading={planning} onSubmit={handlePlan} disabled={executing} />

      {activePlan && (
        <PlanModal
          plan={activePlan}
          executing={executing}
          onApprove={handleApprove}
          onReject={handleReject}
        />
      )}

      {toast && <div className={`toast toast--${toast.kind}`}>{toast.message}</div>}
    </div>
  );
}

export default App
