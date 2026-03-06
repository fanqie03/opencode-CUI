import React, { useState } from 'react';
import type { MessagePart } from '../protocol/types';

interface PermissionCardProps {
    part: MessagePart;
    onDecision?: (permissionId: string, allow: boolean) => void;
}

const permTypeLabels: Record<string, string> = {
    file_write: '文件写入',
    file_read: '文件读取',
    command: '命令执行',
    network: '网络访问',
    unknown: '操作授权',
};

export const PermissionCard: React.FC<PermissionCardProps> = ({
    part,
    onDecision,
}) => {
    const [resolved, setResolved] = useState(part.permResolved ?? false);

    const handleDecision = (allow: boolean) => {
        if (resolved) return;
        setResolved(true);
        if (part.permissionId) {
            onDecision?.(part.permissionId, allow);
        }
    };

    const typeLabel = permTypeLabels[part.permType ?? 'unknown'] ?? part.permType ?? '操作授权';

    return (
        <div className={`permission-card ${resolved ? 'permission-card--resolved' : ''}`}>
            <div className="permission-card__header">
                <span className="permission-card__icon">🔐</span>
                <span className="permission-card__type">{typeLabel}</span>
            </div>

            <div className="permission-card__info">
                {part.toolName && (
                    <div className="permission-card__tool">
                        工具: <strong>{part.toolName}</strong>
                    </div>
                )}
                {part.content && (
                    <div className="permission-card__desc">{part.content}</div>
                )}
            </div>

            {!resolved ? (
                <div className="permission-card__actions">
                    <button
                        className="permission-card__btn permission-card__btn--allow"
                        onClick={() => handleDecision(true)}
                    >
                        ✅ 允许
                    </button>
                    <button
                        className="permission-card__btn permission-card__btn--deny"
                        onClick={() => handleDecision(false)}
                    >
                        ❌ 拒绝
                    </button>
                </div>
            ) : (
                <div className="permission-card__status">已处理</div>
            )}
        </div>
    );
};
