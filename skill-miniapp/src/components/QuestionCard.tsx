import React, { useEffect, useState } from 'react';
import type { MessagePart } from '../protocol/types';

interface QuestionCardProps {
    part: MessagePart;
    onAnswer?: (answer: string, toolCallId?: string) => void;
}

export const QuestionCard: React.FC<QuestionCardProps> = ({ part, onAnswer }) => {
    const optionLabels = part.options?.map((opt) => opt.label) ?? [];
    const [customInput, setCustomInput] = useState('');
    const [selectedAnswer, setSelectedAnswer] = useState(part.toolOutput ?? undefined);
    const [answered, setAnswered] = useState(
        Boolean(part.answered || part.toolStatus === 'completed' || part.toolStatus === 'error'),
    );

    useEffect(() => {
        setAnswered(Boolean(part.answered || part.toolStatus === 'completed' || part.toolStatus === 'error'));
    }, [part.answered, part.partId, part.toolStatus]);

    useEffect(() => {
        const output = part.toolOutput;
        setSelectedAnswer(output ?? undefined);
        if (typeof output === 'string' && !optionLabels.includes(output)) {
            setCustomInput(output);
        }
    }, [optionLabels, part.partId, part.toolOutput]);

    const handleSelect = (label: string) => {
        if (answered) return;
        setAnswered(true);
        setSelectedAnswer(label);
        onAnswer?.(label, part.toolCallId);
    };

    const handleSubmit = () => {
        if (answered || !customInput.trim()) return;
        const answer = customInput.trim();
        setAnswered(true);
        setCustomInput(answer);
        setSelectedAnswer(answer);
        onAnswer?.(answer, part.toolCallId);
    };

    return (
        <div className={`question-card ${answered ? 'question-card--answered' : ''}`}>
            {part.header && (
                <div className="question-card__header">{part.header}</div>
            )}
            <div className="question-card__question">
                <span className="question-card__icon">?</span>
                {part.question ?? part.content}
            </div>

            {answered && selectedAnswer && (
                <div className="question-card__status">已选择：{selectedAnswer}</div>
            )}

            {part.options && part.options.length > 0 && (
                <div className="question-card__options">
                    {part.options.map((opt, i) => (
                        <button
                            key={i}
                            className="question-card__option"
                            onClick={() => handleSelect(opt.label)}
                            disabled={answered}
                        >
                            <span className="question-card__option-label">{opt.label}</span>
                            {opt.description && (
                                <span className="question-card__option-desc">{opt.description}</span>
                            )}
                        </button>
                    ))}
                </div>
            )}

            <div className="question-card__input-group">
                <textarea
                    className="question-card__input"
                    placeholder="输入自定义回答..."
                    value={customInput}
                    onChange={(e) => setCustomInput(e.target.value)}
                    disabled={answered}
                    rows={3}
                />
                <button
                    className="question-card__submit"
                    onClick={handleSubmit}
                    disabled={answered || !customInput.trim()}
                >
                    提交
                </button>
            </div>

            {answered && !selectedAnswer && (
                <div className="question-card__status">已回答</div>
            )}
        </div>
    );
};
