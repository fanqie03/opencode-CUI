import { useState, useEffect, useCallback, useRef } from 'react';
import { getOnlineAgents, AgentInfo } from '../utils/api';

const POLL_INTERVAL_MS = 30_000; // 30 seconds

interface UseAgentSelectorReturn {
    agents: AgentInfo[];
    selectedAgent: AgentInfo | null;
    selectAgent: (agent: AgentInfo) => void;
    loading: boolean;
    error: string | null;
}

export function useAgentSelector(userId: string): UseAgentSelectorReturn {
    const [agents, setAgents] = useState<AgentInfo[]>([]);
    const [selectedAgent, setSelectedAgent] = useState<AgentInfo | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);

    const fetchAgents = useCallback(async () => {
        try {
            const result = await getOnlineAgents(userId);
            setAgents(result);
            setError(null);

            // Auto-select if only one agent and none selected yet
            if (result.length === 1 && !selectedAgent) {
                setSelectedAgent(result[0]);
            }

            // Clear selection if the selected agent went offline
            if (selectedAgent && !result.find((a) => a.id === selectedAgent.id)) {
                setSelectedAgent(result.length > 0 ? result[0] : null);
            }
        } catch (e) {
            setError('Failed to load agents');
            console.error('Agent query error:', e);
        } finally {
            setLoading(false);
        }
    }, [userId, selectedAgent]);

    // Initial fetch + polling
    useEffect(() => {
        void fetchAgents();

        timerRef.current = setInterval(() => {
            void fetchAgents();
        }, POLL_INTERVAL_MS);

        return () => {
            if (timerRef.current) clearInterval(timerRef.current);
        };
    }, [fetchAgents]);

    const selectAgent = useCallback((agent: AgentInfo) => {
        setSelectedAgent(agent);
    }, []);

    return { agents, selectedAgent, selectAgent, loading, error };
}
