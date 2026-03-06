import { useState, useEffect } from 'react';
import ReactMarkdown from 'react-markdown';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { oneDark } from 'react-syntax-highlighter/dist/esm/styles/prism';
import type { MessageHistoryItem } from '../types';
import { APIClient } from '../services/APIClient';
import { config } from '../config';

interface MessageHistoryProps {
    sessionId: string | null;
}

const apiClient = new APIClient(
    config.skillServerUrl.replace(/^ws:/, 'http:').replace(/^wss:/, 'https:')
);

export function MessageHistory({ sessionId }: MessageHistoryProps) {
    const [messages, setMessages] = useState<MessageHistoryItem[]>([]);
    const [page, setPage] = useState(0);
    const [totalPages, setTotalPages] = useState(0);
    const [loading, setLoading] = useState(false);

    const loadMessages = async (pageNum: number) => {
        if (!sessionId) return;
        setLoading(true);
        try {
            const result = await apiClient.getMessages(sessionId, pageNum, 10);
            setMessages(result.content);
            setTotalPages(result.totalPages);
            setPage(pageNum);
        } catch (err) {
            console.error('Failed to load messages:', err);
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        if (sessionId) {
            loadMessages(0);
        } else {
            setMessages([]);
            setPage(0);
            setTotalPages(0);
        }
    }, [sessionId]);

    return (
        <div className="panel">
            <h2>💬 Message History</h2>

            <div className="field-row">
                <strong>Session:</strong> <code>{sessionId || 'None'}</code>
                {sessionId && (
                    <button onClick={() => loadMessages(page)} className="btn btn-secondary btn-sm">
                        Refresh
                    </button>
                )}
            </div>

            {loading ? (
                <p className="placeholder-text">Loading...</p>
            ) : messages.length === 0 ? (
                <p className="placeholder-text">No messages</p>
            ) : (
                <div className="message-list">
                    {messages.map((msg) => (
                        <div
                            key={msg.id}
                            className={`message-item message-role-${msg.role}`}
                        >
                            <div className="message-header">
                                <span className="message-role">{msg.role}</span>
                                <span className="message-meta">
                                    seq: {msg.seq} | {new Date(msg.createdAt).toLocaleTimeString()}
                                </span>
                            </div>
                            <div className="message-content">
                                <ReactMarkdown
                                    components={{
                                        code({ className, children, ...props }) {
                                            const match = /language-(\w+)/.exec(className || '');
                                            const codeString = String(children).replace(/\n$/, '');
                                            return match ? (
                                                <SyntaxHighlighter
                                                    style={oneDark}
                                                    language={match[1]}
                                                    PreTag="div"
                                                >
                                                    {codeString}
                                                </SyntaxHighlighter>
                                            ) : (
                                                <code className={className} {...props}>
                                                    {children}
                                                </code>
                                            );
                                        },
                                    }}
                                >
                                    {msg.content}
                                </ReactMarkdown>
                            </div>
                        </div>
                    ))}
                </div>
            )}

            {totalPages > 1 && (
                <div className="pagination">
                    <button
                        onClick={() => loadMessages(page - 1)}
                        disabled={page === 0}
                        className="btn btn-secondary btn-sm"
                    >
                        ← Prev
                    </button>
                    <span>
                        Page {page + 1} / {totalPages}
                    </span>
                    <button
                        onClick={() => loadMessages(page + 1)}
                        disabled={page >= totalPages - 1}
                        className="btn btn-secondary btn-sm"
                    >
                        Next →
                    </button>
                </div>
            )}
        </div>
    );
}
