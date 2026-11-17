export interface IReqiestError {
    title: string;
    status: number;
    detail: string;
    instance: string;
}

export class RequestError extends Error {
    public readonly title: string;
    public readonly status: number;
    public readonly detail: string;
    public readonly instance: string;

    constructor(obj: IReqiestError) {
        super(obj.detail);
        this.title = obj.title;
        this.status = obj.status;
        this.detail = obj.detail;
        this.instance = obj.instance;
    }
}

export function isError<T extends object>(v: T | IReqiestError): v is IReqiestError {
    const keys = new Set(Object.keys(v));

    return keys.has('title') && keys.has('status') && keys.has('detail') && keys.has('intance');
}
