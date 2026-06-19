declare module 'ali-oss' {
  export default class OSS {
    constructor(options: Record<string, unknown>);
    put(key: string, value: File, options?: Record<string, unknown>): Promise<unknown>;
  }
}
