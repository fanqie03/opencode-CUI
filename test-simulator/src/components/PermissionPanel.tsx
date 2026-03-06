import { useState } from 'react';
import type { PermissionRequest } from '../types';
import { APIClient } from '../services/APIClient';
import { config } from '../config';

interface PermissionPanelProps {
    permissions: PermissionRequest[];
    onPermissionHandled: (permId: string) => void;
}

const apiClient = new APIClient(
    config.skillServerUrl.replace(/^ws:/, 'http:').replace(/^wss:/, 'https:')
);

export function PermissionPanel({ permissions, onPermissionHandled }: PermissionPanelProps) {
    const [processing, setProcessing] = useState<string | null>(null);

    const handleReply = async (perm: PermissionRequest, approved: boolean) => {
        setProcessing(perm.permissionId);
        try {
            await apiClient.replyPermission(perm.sessionId, perm.permissionId, {
                approved,
                reason: approved ? 'User approved' : 'User rejected',
            });
            onPermissionHandled(perm.permissionId);
        } catch (err) {
            alert(`Failed to reply permission: ${err}`);
        } finally {
            setProcessing(null);
        }
    };

    return (
        <div className="panel">
            <h2>🔐 Permission Requests</h2>

            {permissions.length === 0 ? (
                <p className="placeholder-text">No pending permissions</p>
            ) : (
                <ul className="permission-list">
                    {permissions.map((perm) => (
                        <li key={perm.permissionId} className="permission-item">
                            <div className="permission-info">
                                <strong>{perm.type}</strong>
                                <p>{perm.description}</p>
                                <small>
                                    Session: {perm.sessionId} | ID: {perm.permissionId}
                                </small>
                            </div>
                            <div className="permission-actions">
                                <button
                                    onClick={() => handleReply(perm, true)}
                                    disabled={processing === perm.permissionId}
                                    className="btn btn-success btn-sm"
                                >
                                    ✓ Approve
                                </button>
                                <button
                                    onClick={() => handleReply(perm, false)}
                                    disabled={processing === perm.permissionId}
                                    className="btn btn-danger btn-sm"
                                >
                                    ✗ Reject
                                </button>
                            </div>
                        </li>
                    ))}
                </ul>
            )}
        </div>
    );
}
