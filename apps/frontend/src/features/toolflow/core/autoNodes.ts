let _loaded = false;

// Note: Vite requires string literals directly in import.meta.glob
export function autoRegisterAllNodes(_patterns?: string[], allowRepeat?: boolean): string[] {
    const repeat = allowRepeat ?? false;
    if (!repeat && _loaded) return [];
    _loaded = true;

    try {
        const modules = import.meta.glob('../nodes/**/*.{ts,tsx}', { eager: true }) as Record<string, unknown>;
        return Object.keys(modules);
    } catch (e) {
        console.warn("[toolflow] autoRegisterAllNodes error:", '../nodes/**/*.{ts,tsx}', e);
        return [];
    }
}

export async function autoRegisterAllNodesAsync(_patterns?: string[], allowRepeat?: boolean): Promise<string[]> {
    const repeat = allowRepeat ?? false;
    if (!repeat && _loaded) return [];
    _loaded = true;

    try {
        const loaders = import.meta.glob('../nodes/**/*.{ts,tsx}') as Record<string, () => Promise<unknown>>;
        const entries = Object.entries(loaders) as Array<[string, () => Promise<unknown>]>;
        await Promise.all(entries.map(([, loader]) => loader()));
        return entries.map(([p]) => p);
    } catch (e) {
        console.warn("[toolflow] autoRegisterAllNodesAsync error:", '../nodes/**/*.{ts,tsx}', e);
        return [];
    }
}
