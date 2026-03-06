import React, { useState } from 'react';
import type { MessagePart } from '../protocol/types';

interface QuestionCardProps {
    part: MessagePart;
    onAnswer?: (answer: string) => void;
}

export const QuestionCard: React.FC<QuestionCardProps> = ({ part, onAnswer }) => {
    const [customInput, setCustomInput] = useState('');
    const [answered, setAnswered] = useState(part.answered ?? false);

    const handleSelect = (option: string) => {
        if (answered) return;
        setAnswered(true);
        onAnswer?.(option);
    };

    const handleSubmit = () => {
        if (answered || !customInput.trim()) return;
        setAnswered(true);
        onAnswer?.(customInput.trim());
    };

    return (
        <div className={`question-card ${answered ? 'question-card--answered' : ''}`}>
            {part.header && (
                <div className="question-card__header">{part.header}</div>
            )}
            <div className="question-card__question">
                <span className="question-card__icon">❓</span>
                {part.question ?? part.content}
            </div>

            {part.options && part.options.length > 0 && (
                <div className="question-card__options">
                    {part.options.map((opt, i) => (
                        <button
                            key={i}
                            className="question-card__option"
                            onClick={() => handleSelect(opt)}
                            disabled={answered}
                        >
                            {opt}
                        </button>
                    ))}
                </div>
            )}

            <div className="question-card__input-group">
                <input
                    type="text"
                    className="question-card__input"
                    placeholder="输入自定义回答..."
                    value={customInput}
                    onChange={(e) => setCustomInput(e.target.value)}
                    onKeyDown={(e) => e.key === 'Enter' && handleSubmit()}
                    disabled={answered}
                />
                <button
                    className="question-card__submit"
                    onClick={handleSubmit}
                    disabled={answered || !customInput.trim()}
                >
                    提交
                </button>
            </div>

            {answered && (
                <div className="question-card__status">✅ 已回答</div>
            )}
        </div>
    );
};
