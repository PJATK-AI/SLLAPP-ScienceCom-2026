/** Error body that the backend sends as `application/problem+json` (RFC 9457). */
export interface ProblemDetail {
    type?: string;
    title?: string;
    status?: number;
    detail?: string;
    instance?: string;
}

/** Error for an HTTP response with a status outside the 200–299 range. */
export class RequestError extends Error {
    readonly status: number;
    readonly problem: ProblemDetail | null;

    constructor(status: number, problem: ProblemDetail | null = null) {
        super(problem?.detail ?? problem?.title ?? `HTTP Error: ${status}`);
        this.name = 'RequestError';
        this.status = status;
        this.problem = problem;
    }
}

export function isProblemDetail(value: unknown): value is ProblemDetail {
    if (typeof value !== 'object' || value === null || Array.isArray(value)) {
        return false;
    }
    const problem = value as Record<string, unknown>;
    return (
        ['type', 'title', 'detail', 'instance'].every(
            (key) => problem[key] === undefined || typeof problem[key] === 'string',
        ) &&
        (problem.status === undefined || typeof problem.status === 'number')
    );
}
