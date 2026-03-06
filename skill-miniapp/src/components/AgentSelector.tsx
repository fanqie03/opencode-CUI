import React, { useState, useRef, useEffect } from 'react';
import type { AgentInfo } from '../utils/api';

interface AgentSelectorProps {
    agents: AgentInfo[];
    selectedAgent: AgentInfo | null;
    onSelect: (agent: AgentInfo) => void;
    loading?: boolean;
}

const osIcons: Record<string, string> = {
    windows: '🪟',
    linux: '🐧',
    macos: '🍎',
    darwin: '🍎',
};

function getOsIcon(os: string): string {
    const lower = os?.toLowerCase() ?? '';
    for (const [key, icon] of Object.entries(osIcons)) {
        if (lower.includes(key)) return icon;
    }
    return '💻';
}

export const AgentSelector: React.FC<AgentSelectorProps> = ({
    agents,
    selectedAgent,
    onSelect,
    loading,
}) => {
    const [open, setOpen] = useState(false);
    const containerRef = useRef<HTMLDivElement>(null);

    // Close dropdown on outside click
    useEffect(() => {
        const handler = (e: MouseEvent) => {
            if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
                setOpen(false);
            }
        };
        document.addEventListener('mousedown', handler);
        return () => document.removeEventListener('mousedown', handler);
    }, []);

    if (loading) {
        return (
            <div className="agent-selector-bar">
                <div className="agent-capsule agent-capsule--loading">
                    <span className="agent-capsule__dot agent-capsule__dot--loading" />
                    <span className="agent-capsule__label">正在加载 Agent...</span>
                </div>
            </div>
        );
    }

    if (agents.length === 0) {
        return (
            <div className="agent-selector-bar">
                <div className="agent-capsule agent-capsule--empty">
                    <span className="agent-capsule__dot agent-capsule__dot--offline" />
                    <span className="agent-capsule__label">无可用 Agent — 请先启动 OpenCode</span>
                </div>
            </div>
        );
    }

    return (
        <div className="agent-selector-bar" ref={containerRef}>
            <button
                type="button"
                className={`agent-capsule ${open ? 'agent-capsule--open' : ''}`}
                onClick={() => setOpen((v) => !v)}
            >
                <span className="agent-capsule__dot agent-capsule__dot--online" />
                <span className="agent-capsule__icon">
                    {selectedAgent ? getOsIcon(selectedAgent.os) : '💻'}
                </span>
                <span className="agent-capsule__label">
                    {selectedAgent
                        ? `${selectedAgent.deviceName}`
                        : '选择 Agent'}
                </span>
                {selectedAgent && (
                    <span className="agent-capsule__meta">{selectedAgent.toolType}</span>
                )}
                <span className="agent-capsule__arrow">{open ? '▴' : '▾'}</span>
            </button>

            {open && (
                <div className="agent-dropdown">
                    {agents.map((agent) => (
                        <button
                            key={agent.id}
                            type="button"
                            className={`agent-dropdown__item ${selectedAgent?.id === agent.id ? 'agent-dropdown__item--active' : ''
                                }`}
                            onClick={() => {
                                onSelect(agent);
                                setOpen(false);
                            }}
                        >
                            <span className="agent-capsule__dot agent-capsule__dot--online" />
                            <span className="agent-dropdown__icon">{getOsIcon(agent.os)}</span>
                            <div className="agent-dropdown__info">
                                <span className="agent-dropdown__name">{agent.deviceName}</span>
                                <span className="agent-dropdown__detail">
                                    {agent.toolType} {agent.toolVersion ? `v${agent.toolVersion}` : ''}
                                </span>
                            </div>
                        </button>
                    ))}
                </div>
            )}
        </div>
    );
};
