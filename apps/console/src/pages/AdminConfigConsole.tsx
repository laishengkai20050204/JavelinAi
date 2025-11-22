import React, { useEffect, useMemo, useState } from "react";
import { motion } from "framer-motion";
import {
    Save,
    RefreshCw,
    Wand2,
    Eye,
    EyeOff,
    Trash2,
    PlusCircle,
    MinusCircle,
} from "lucide-react";

type Lang = "zh" | "en";

type ModelProfile = {
    provider?: string;
    baseUrl?: string;
    apiKey?: string;
    modelId?: string;
    temperature?: number;
    maxTokens?: number;
    timeoutMs?: number;
    toolsEnabled?: boolean;
    toolContextRenderMode?: string;
};

type RuntimeCfg = {
    profile?: string;
    toolsMaxLoops?: number;
    memoryMaxMessages?: number | null;
    toolToggles?: Record<string, boolean>;
    profiles?: Record<string, ModelProfile>;
};

type ConfigResp = {
    runtime: RuntimeCfg;
    effective: RuntimeCfg;
    availableTools?: string[];
    availableProfiles?: string[];
};

const I18N = {
    zh: {
        title: "Javelin Config Console",
        subtitle: "Runtime overrides - Effective config",
        actions: {
            reload: "Reload",
            reloading: "Reloading...",
            refresh: "Refresh",
            save: "Save",
            saving: "Saving...",
            revert: "Revert unsaved",
            restore: "Restore defaults",
            restoring: "Restoring...",
            diffOpen: "Show pending diff",
            diffClose: "Hide diff",
            willSubmit: "Will submit",
            effectiveOpen: "Show effective",
            effectiveClose: "Hide effective",
        },
        banners: {
            loading: "Loading configuration",
            saved: "Configuration saved",
            reloaded: "Reload triggered",
            restored: "Restored to defaults",
        },
        sections: {
            snapshots: { runtime: "Runtime overrides" },
            basics: "Basics",
            toggles: "Tool toggles (toolToggles)",
            none: "(No explicit toggles yet)",
            defaultOn: "Unspecified tools default to enabled (true)",
            profiles: "Available profiles",
            runtimeProfiles: "Runtime profiles (create / delete)",
        },
        fields: {
            profile: "Model profile",
            loops: "Max tool loops (toolsMaxLoops)",
            memoryMax: "Memory window (memoryMaxMessages)",
            name: "Profile name",
            provider: "Provider",
            modelId: "Model ID",
            baseUrl: "Base URL",
            apiKey: "API Key",
            temperature: "Temperature",
            maxTokens: "Max tokens",
            timeoutMs: "Timeout (ms)",
        },
        tooltips: { reload: "Broadcast a reload", refresh: "Refresh config" },
        placeholders: { profile: "gpt-main / glm-agent ..." },
        on: "On",
        off: "Off",
        confirm: { restore: "Restore defaults? This clears runtime overrides." },
    },
    en: {
        title: "Javelin Config Console",
        subtitle: "Runtime overrides - Effective config",
        actions: {
            reload: "Reload",
            reloading: "Reloading...",
            refresh: "Refresh",
            save: "Save",
            saving: "Saving...",
            revert: "Revert unsaved",
            restore: "Restore defaults",
            restoring: "Restoring...",
            diffOpen: "Show pending diff",
            diffClose: "Hide diff",
            willSubmit: "Will submit",
            effectiveOpen: "Show effective",
            effectiveClose: "Hide effective",
        },
        banners: {
            loading: "Loading configuration",
            saved: "Configuration saved",
            reloaded: "Reload triggered",
            restored: "Restored to defaults",
        },
        sections: {
            snapshots: { runtime: "Runtime overrides" },
            basics: "Basics",
            toggles: "Tool toggles (toolToggles)",
            none: "(No explicit toggles yet)",
            defaultOn: "Unspecified tools default to enabled (true)",
            profiles: "Available profiles",
            runtimeProfiles: "Runtime profiles (create / delete)",
        },
        fields: {
            profile: "Model profile",
            loops: "Max tool loops (toolsMaxLoops)",
            memoryMax: "Memory window (memoryMaxMessages)",
            name: "Profile name",
            provider: "Provider",
            modelId: "Model ID",
            baseUrl: "Base URL",
            apiKey: "API Key",
            temperature: "Temperature",
            maxTokens: "Max tokens",
            timeoutMs: "Timeout (ms)",
        },
        tooltips: { reload: "Broadcast a reload", refresh: "Refresh config" },
        placeholders: { profile: "gpt-main / glm-agent ..." },
        on: "On",
        off: "Off",
        confirm: { restore: "Restore defaults? This clears runtime overrides." },
    },
} as const;

export default function AdminConfigConsole() {
    const [lang, setLang] = useState<Lang>(() =>
        typeof navigator !== "undefined" &&
        navigator.language?.toLowerCase().startsWith("zh")
            ? "zh"
            : "en"
    );
    const t = I18N[lang];

    const [loading, setLoading] = useState(false);
    const [saving, setSaving] = useState(false);
    const [reloading, setReloading] = useState(false);
    const [resetting, setResetting] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [okMsg, setOkMsg] = useState<string | null>(null);

    const [runtime, setRuntime] = useState<RuntimeCfg | null>(null);
    const [, setEffective] = useState<RuntimeCfg | null>(null);
    const [availableTools, setAvailableTools] = useState<string[]>([]);
    const [availableProfiles, setAvailableProfiles] = useState<string[]>([]);
    const [runtimeProfiles, setRuntimeProfiles] = useState<
        Record<string, ModelProfile>
    >({});

    const [profile, setProfile] = useState<string>("");
    const [toolsMaxLoops, setToolsMaxLoops] = useState<number>(10);
    const [memoryMaxMessages, setMemoryMaxMessages] = useState<number | "">("");
    const [toolToggles, setToolToggles] = useState<Record<string, boolean>>({});

    const [showDiff, setShowDiff] = useState(false);

    const [runtimeProfileName, setRuntimeProfileName] = useState("");
    const [runtimeProfileData, setRuntimeProfileData] = useState<ModelProfile>(
        {}
    );

    const mergedProfileOptions = useMemo(() => {
        const set = new Set<string>(availableProfiles);
        Object.keys(runtimeProfiles || {}).forEach((k) => set.add(k));
        return Array.from(set);
    }, [availableProfiles, runtimeProfiles]);

    const diffPayload = useMemo(() => {
        if (!runtime) return null;
        const diff: Record<string, unknown> = {};
        // 始终显式传递 profile（空字符串代表 null）
        diff.profile = profile === "" ? null : profile;
        if (toolsMaxLoops !== (runtime.toolsMaxLoops ?? 10)) {
            diff.toolsMaxLoops = toolsMaxLoops;
        }
        const memoryValue =
            memoryMaxMessages === "" ? null : (memoryMaxMessages as number);
        if (memoryValue !== (runtime.memoryMaxMessages ?? null)) {
            diff.memoryMaxMessages = memoryValue;
        }
        const normalized = Object.fromEntries(
            Object.entries(toolToggles).filter(([, v]) => v !== true)
        );
        if (
            JSON.stringify(normalized) !==
            JSON.stringify(runtime.toolToggles ?? {})
        ) {
            diff.toolToggles = normalized;
        }
        if (
            JSON.stringify(runtimeProfiles ?? {}) !==
            JSON.stringify(runtime.profiles ?? {})
        ) {
            diff.profiles = runtimeProfiles;
        }
        return diff;
    }, [profile, toolsMaxLoops, memoryMaxMessages, toolToggles, runtimeProfiles, runtime]);

    const isDiffEmpty = !diffPayload || Object.keys(diffPayload).length === 0;

    const resetForm = () => {
        if (!runtime) return;
        setProfile(runtime.profile ?? "");
        setToolsMaxLoops(runtime.toolsMaxLoops ?? 10);
        setMemoryMaxMessages(runtime.memoryMaxMessages ?? "");
        setToolToggles(runtime.toolToggles ?? {});
        setRuntimeProfiles(runtime.profiles ?? {});
        setShowDiff(false);
    };

    const refresh = async () => {
        setLoading(true);
        setError(null);
        try {
            const resp = await fetch("/admin/config");
            if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
            const data: ConfigResp = await resp.json();
            const r: RuntimeCfg = data.runtime ?? {};
            const e: RuntimeCfg = data.effective ?? {};
            setRuntime(r);
            setEffective(e);
            setAvailableTools(data.availableTools ?? []);
            setAvailableProfiles(data.availableProfiles ?? []);
            setProfile(r.profile ?? "");
            setToolsMaxLoops(r.toolsMaxLoops ?? 10);
            setMemoryMaxMessages(r.memoryMaxMessages ?? "");
            setToolToggles(r.toolToggles ?? {});
            setRuntimeProfiles(r.profiles ?? {});
        } catch (e) {
            setError(errorMessage(e) ?? "Failed to load config");
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        void refresh();
    }, []);

    const save = async () => {
        if (!diffPayload || Object.keys(diffPayload).length === 0) return;
        setSaving(true);
        setError(null);
        setOkMsg(null);
        try {
            const resp = await fetch("/admin/config", {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(diffPayload),
            });
            if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
            await refresh();
            setOkMsg(t.banners.saved);
        } catch (e) {
            setError(errorMessage(e) ?? "Save failed");
        } finally {
            setSaving(false);
        }
    };

    const reload = async () => {
        setReloading(true);
        setError(null);
        setOkMsg(null);
        try {
            const resp = await fetch("/admin/reload", { method: "POST" });
            if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
            setOkMsg(t.banners.reloaded);
        } catch (e) {
            setError(errorMessage(e) ?? "Reload failed");
        } finally {
            setReloading(false);
        }
    };

    const restoreDefaults = async () => {
        if (
            typeof window !== "undefined" &&
            !window.confirm(t.confirm.restore)
        ) {
            return;
        }
        setResetting(true);
        setError(null);
        setOkMsg(null);
        try {
            const resp = await fetch("/admin/config/replace", {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({}),
            });
            if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
            setOkMsg(t.banners.restored);
            await refresh();
        } catch (e) {
            setError(errorMessage(e) ?? "Restore failed");
        } finally {
            setResetting(false);
        }
    };

    const toolNames = useMemo(() => {
        const all = new Set<string>([
            ...availableTools,
            ...Object.keys(toolToggles),
        ]);
        return Array.from(all).sort();
    }, [availableTools, toolToggles]);

    return (
        <div className="min-h-screen bg-slate-100 text-slate-900 dark:bg-slate-950 dark:text-slate-100">
            <main className="mx-auto max-w-6xl space-y-4 px-4 py-6">
                <header className="flex flex-col gap-2 md:flex-row md:items-center md:justify-between">
                    <div>
                        <div className="flex items-center gap-2">
                            <Wand2 className="h-5 w-5 text-blue-500" />
                            <h1 className="text-xl font-semibold">{t.title}</h1>
                            <span className="text-xs text-slate-500">{t.subtitle}</span>
                        </div>
                        <div className="flex items-center gap-3 text-xs text-slate-500">
                            <button
                                onClick={() => setLang(lang === "zh" ? "en" : "zh")}
                                className="rounded border border-slate-300 px-2 py-1 text-xs font-medium dark:border-slate-700"
                            >
                                {lang === "zh" ? "Switch to EN" : "Switch to ZH"}
                            </button>
                            <button
                                onClick={refresh}
                                className="inline-flex items-center gap-1 rounded border border-slate-300 px-2 py-1 text-xs dark:border-slate-700"
                            >
                                <RefreshCw size={14} /> {t.actions.refresh}
                            </button>
                            <button
                                onClick={reload}
                                disabled={reloading}
                                className="inline-flex items-center gap-1 rounded border border-blue-300 bg-blue-50 px-2 py-1 text-xs text-blue-700 dark:border-blue-700 dark:bg-blue-900/40 dark:text-blue-200"
                            >
                                <RefreshCw size={14} />{" "}
                                {reloading ? t.actions.reloading : t.actions.reload}
                            </button>
                        </div>
                    </div>
                    <div className="flex flex-wrap gap-2 text-xs">
                        {loading && (
                            <Banner
                                color="slate"
                                text={t.banners.loading}
                                icon={<RefreshCw className="h-4 w-4" />}
                            />
                        )}
                        {okMsg && (
                            <Banner
                                color="green"
                                text={okMsg}
                                icon={<Wand2 className="h-4 w-4" />}
                            />
                        )}
                        {error && (
                            <Banner
                                color="red"
                                text={error}
                                icon={<Trash2 className="h-4 w-4" />}
                            />
                        )}
                    </div>
                </header>

                <motion.div
                    initial={{ opacity: 0, y: 6 }}
                    animate={{ opacity: 1, y: 0 }}
                    className="space-y-3"
                >
                    <Card title={t.sections.snapshots.runtime}>
                        <Snap k="profile" v={runtime?.profile ?? "-"} />
                        <Snap k="toolsMaxLoops" v={runtime?.toolsMaxLoops ?? "-"} />
                        <Snap
                            k="memoryMaxMessages"
                            v={runtime?.memoryMaxMessages ?? "-"}
                        />
                    </Card>
                </motion.div>

                <motion.div
                    initial={{ opacity: 0, y: 6 }}
                    animate={{ opacity: 1, y: 0 }}
                    className="space-y-4 rounded-2xl border bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900"
                >
                    <Section title={t.sections.basics}>
                        <div className="grid gap-4 md:grid-cols-3">
                            <Field label={t.fields.profile}>
                                {mergedProfileOptions.length > 0 ? (
                                    <select
                                        value={profile}
                                        onChange={(e) => setProfile(e.target.value)}
                                        className="w-full rounded-xl border border-slate-300 bg-white p-2 text-slate-900 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
                                    >
                                        <option value="">{t.placeholders.profile}</option>
                                        {mergedProfileOptions.map((p) => (
                                            <option key={p} value={p}>
                                                {p}
                                            </option>
                                        ))}
                                    </select>
                                ) : (
                                    <input
                                        value={profile}
                                        onChange={(e) => setProfile(e.target.value)}
                                        placeholder={t.placeholders.profile}
                                        className="w-full rounded-xl border border-slate-300 bg-white p-2 text-slate-900 placeholder-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:placeholder-slate-400"
                                    />
                                )}
                            </Field>
                            <Field label={t.fields.loops}>
                                <input
                                    type="number"
                                    min={0}
                                    value={toolsMaxLoops}
                                    onChange={(e) => setToolsMaxLoops(Number(e.target.value))}
                                    className="w-full rounded-xl border border-slate-300 bg-white p-2 text-slate-900 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
                                />
                            </Field>
                            <Field label={t.fields.memoryMax}>
                                <input
                                    type="number"
                                    min={1}
                                    value={memoryMaxMessages}
                                    onChange={(e) =>
                                        setMemoryMaxMessages(
                                            e.target.value === "" ? "" : Number(e.target.value)
                                        )
                                    }
                                    className="w-full rounded-xl border border-slate-300 bg-white p-2 text-slate-900 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
                                />
                            </Field>
                        </div>
                    </Section>

                    <Section title={t.sections.toggles}>
                        <div className="space-y-3">
                            <div className="text-xs text-slate-500 dark:text-slate-400">
                                {t.sections.defaultOn}
                            </div>
                            {toolNames.length === 0 ? (
                                <div className="text-sm text-slate-500 dark:text-slate-400">
                                    {t.sections.none}
                                </div>
                            ) : (
                                <div className="flex flex-wrap gap-2">
                                    {toolNames.map((name) => {
                                        const enabled = toolToggles[name] !== false;
                                        return (
                                            <label
                                                key={name}
                                                className={`flex select-none items-center gap-2 rounded-full border px-3 py-1 text-sm cursor-pointer ${
                                                    enabled
                                                        ? "border-green-300 bg-green-50 text-green-700 dark:border-emerald-800 dark:bg-emerald-900/30 dark:text-emerald-200"
                                                        : "border-slate-300 bg-white text-slate-700 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200"
                                                }`}
                                                title={name}
                                            >
                                                <input
                                                    type="checkbox"
                                                    className="accent-blue-600 dark:accent-blue-400"
                                                    checked={enabled}
                                                    onChange={(e) => {
                                                        const nextChecked = e.target.checked;
                                                        setToolToggles((prev) => {
                                                            const next: Record<string, boolean> = {
                                                                ...prev,
                                                            };
                                                            if (nextChecked) delete next[name];
                                                            else next[name] = false;
                                                            return next;
                                                        });
                                                    }}
                                                />
                                                <span className="font-mono">{name}</span>
                                                <span className="text-xs opacity-70">
                          {enabled ? t.on : t.off}
                        </span>
                                            </label>
                                        );
                                    })}
                                </div>
                            )}
                        </div>
                    </Section>

                    <Section title={t.sections.runtimeProfiles}>
                        <div className="grid gap-3 md:grid-cols-4">
                            <Field label={t.fields.name}>
                                <input
                                    value={runtimeProfileName}
                                    onChange={(e) => setRuntimeProfileName(e.target.value)}
                                    placeholder="profile key, e.g. live-test"
                                    className="w-full rounded-xl border border-slate-300 bg-white p-2 text-slate-900 placeholder-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:placeholder-slate-400"
                                />
                            </Field>
                            <Field label={t.fields.provider}>
                                <input
                                    value={runtimeProfileData.provider ?? ""}
                                    onChange={(e) =>
                                        setRuntimeProfileData((p) => ({
                                            ...p,
                                            provider: e.target.value,
                                        }))
                                    }
                                    placeholder="openai / openai-compatible / gemini ..."
                                    className="w-full rounded-xl border border-slate-300 bg-white p-2 text-slate-900 placeholder-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:placeholder-slate-400"
                                />
                            </Field>
                            <Field label={t.fields.modelId}>
                                <input
                                    value={runtimeProfileData.modelId ?? ""}
                                    onChange={(e) =>
                                        setRuntimeProfileData((p) => ({
                                            ...p,
                                            modelId: e.target.value,
                                        }))
                                    }
                                    placeholder="gpt-4.1 / glm-4 / ..."
                                    className="w-full rounded-xl border border-slate-300 bg-white p-2 text-slate-900 placeholder-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:placeholder-slate-400"
                                />
                            </Field>
                            <Field label={t.fields.baseUrl}>
                                <input
                                    value={runtimeProfileData.baseUrl ?? ""}
                                    onChange={(e) =>
                                        setRuntimeProfileData((p) => ({
                                            ...p,
                                            baseUrl: e.target.value,
                                        }))
                                    }
                                    placeholder="https://api.xxx.com/v1"
                                    className="w-full rounded-xl border border-slate-300 bg-white p-2 text-slate-900 placeholder-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:placeholder-slate-400"
                                />
                            </Field>
                            <Field label={t.fields.apiKey}>
                                <input
                                    value={runtimeProfileData.apiKey ?? ""}
                                    onChange={(e) =>
                                        setRuntimeProfileData((p) => ({
                                            ...p,
                                            apiKey: e.target.value,
                                        }))
                                    }
                                    placeholder="sk-..."
                                    className="w-full rounded-xl border border-slate-300 bg-white p-2 text-slate-900 placeholder-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:placeholder-slate-400"
                                />
                            </Field>
                            <Field label={t.fields.temperature}>
                                <input
                                    type="number"
                                    step="0.1"
                                    value={runtimeProfileData.temperature ?? ""}
                                    onChange={(e) =>
                                        setRuntimeProfileData((p) => ({
                                            ...p,
                                            temperature:
                                                e.target.value === ""
                                                    ? undefined
                                                    : Number(e.target.value),
                                        }))
                                    }
                                    className="w-full rounded-xl border border-slate-300 bg-white p-2 text-slate-900 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
                                />
                            </Field>
                            <Field label={t.fields.maxTokens}>
                                <input
                                    type="number"
                                    value={runtimeProfileData.maxTokens ?? ""}
                                    onChange={(e) =>
                                        setRuntimeProfileData((p) => ({
                                            ...p,
                                            maxTokens:
                                                e.target.value === ""
                                                    ? undefined
                                                    : Number(e.target.value),
                                        }))
                                    }
                                    className="w-full rounded-xl border border-slate-300 bg-white p-2 text-slate-900 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
                                />
                            </Field>
                            <Field label={t.fields.timeoutMs}>
                                <input
                                    type="number"
                                    value={runtimeProfileData.timeoutMs ?? ""}
                                    onChange={(e) =>
                                        setRuntimeProfileData((p) => ({
                                            ...p,
                                            timeoutMs:
                                                e.target.value === ""
                                                    ? undefined
                                                    : Number(e.target.value),
                                        }))
                                    }
                                    className="w-full rounded-xl border border-slate-300 bg-white p-2 text-slate-900 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
                                />
                            </Field>
                            <div className="md:col-span-4 flex flex-wrap items-center gap-3">
                                <button
                                    onClick={() => {
                                        if (!runtimeProfileName.trim()) {
                                            setError("Profile name is required");
                                            return;
                                        }
                                        if (
                                            !runtimeProfileData.provider ||
                                            !runtimeProfileData.modelId
                                        ) {
                                            setError("provider and modelId are required");
                                            return;
                                        }
                                        setRuntimeProfiles((prev) => ({
                                            ...prev,
                                            [runtimeProfileName.trim()]: {
                                                ...runtimeProfileData,
                                            },
                                        }));
                                        setOkMsg("Profile added to pending diff, click Save");
                                    }}
                                    className="inline-flex items-center gap-2 rounded-xl border border-slate-300 bg-white px-4 py-2 text-sm text-slate-700 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:hover:bg-slate-700"
                                >
                                    <PlusCircle size={16} /> Save to pending diff
                                </button>
                                {Object.keys(runtimeProfiles ?? {}).length > 0 && (
                                    <div className="text-xs text-slate-500 dark:text-slate-400">
                                        Current runtime profiles:{" "}
                                        {Object.keys(runtimeProfiles).join(", ")}
                                    </div>
                                )}
                            </div>
                            {Object.keys(runtimeProfiles ?? {}).length > 0 && (
                                <div className="md:col-span-4">
                                    <div className="mb-1 text-xs text-slate-500 dark:text-slate-400">
                                        Click to remove (will apply on Save)
                                    </div>
                                    <div className="flex flex-wrap gap-2">
                                        {Object.keys(runtimeProfiles).map((name) => (
                                            <button
                                                key={name}
                                                onClick={() =>
                                                    setRuntimeProfiles((prev) => {
                                                        const next = { ...prev };
                                                        delete next[name];
                                                        return next;
                                                    })
                                                }
                                                className="rounded-full border border-red-300 bg-white px-3 py-1 text-xs text-red-700 hover:bg-red-50 dark:border-red-700 dark:bg-slate-800 dark:text-red-300 dark:hover:bg-red-900/30"
                                            >
                                                <MinusCircle
                                                    size={14}
                                                    className="mr-1 inline align-middle"
                                                />
                                                Delete {name}
                                            </button>
                                        ))}
                                    </div>
                                </div>
                            )}
                        </div>
                    </Section>

                    <div className="mt-3 flex flex-wrap items-center gap-3 md:sticky md:bottom-4 md:z-10 md:rounded-2xl md:border md:border-slate-200 md:bg-slate-50/80 md:p-3 md:backdrop-blur md:supports-[backdrop-filter]:bg-slate-50/60 transition-colors dark:md:border-slate-800 dark:md:bg-slate-900/70 dark:md:supports-[backdrop-filter]:bg-slate-900/60">
                        <button
                            onClick={save}
                            disabled={saving || isDiffEmpty}
                            className={`inline-flex items-center gap-2 rounded-xl px-4 py-2 text-sm font-medium ${
                                saving || isDiffEmpty
                                    ? "bg-slate-300 text-white dark:bg-slate-700"
                                    : "bg-blue-600 text-white hover:bg-blue-700 dark:bg-blue-500 dark:hover:bg-blue-400"
                            }`}
                        >
                            <Save size={16} />{" "}
                            {saving ? t.actions.saving : t.actions.save}
                        </button>
                        <button
                            onClick={resetForm}
                            className="inline-flex items-center gap-2 rounded-xl border border-slate-300 bg-white px-4 py-2 text-sm text-slate-700 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:hover:bg-slate-700"
                        >
                            <RefreshCw size={16} /> {t.actions.revert}
                        </button>
                        <button
                            onClick={restoreDefaults}
                            disabled={resetting}
                            className="inline-flex items-center gap-2 rounded-xl border border-red-300 bg-white px-4 py-2 text-sm text-red-700 hover:bg-red-50 dark:border-red-700 dark:bg-slate-800 dark:text-red-300 dark:hover:bg-red-900/30 disabled:opacity-60"
                        >
                            <Trash2 size={16} />{" "}
                            {resetting ? t.actions.restoring : t.actions.restore}
                        </button>
                        <button
                            onClick={() => setShowDiff(!showDiff)}
                            className="inline-flex items-center gap-2 rounded-xl border border-slate-300 bg-white px-4 py-2 text-sm text-slate-700 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:hover:bg-slate-700"
                        >
                            {showDiff ? (
                                <EyeOff size={16} />
                            ) : (
                                <Eye size={16} />
                            )}{" "}
                            {showDiff ? t.actions.diffClose : t.actions.diffOpen}
                        </button>
                        {diffPayload && Object.keys(diffPayload).length > 0 && (
                            <span className="self-center text-xs text-slate-500 dark:text-slate-400">
                {t.actions.willSubmit}:{" "}
                                {Object.keys(diffPayload).join(", ")}
              </span>
                        )}
                    </div>

                    {showDiff && (
                        <motion.pre
                            initial={{ opacity: 0 }}
                            animate={{ opacity: 1 }}
                            className="mt-4 overflow-auto rounded-xl bg-slate-900 p-4 text-xs text-slate-100 dark:bg-black"
                        >
                            {JSON.stringify(diffPayload, null, 2)}
                        </motion.pre>
                    )}
                </motion.div>
            </main>
        </div>
    );
}

function Section({
                     title,
                     children,
                 }: {
    title: string;
    children: React.ReactNode;
}) {
    return (
        <section className="py-4">
            <div className="mb-3 text-sm font-medium text-slate-700 dark:text-slate-200">
                {title}
            </div>
            {children}
        </section>
    );
}

function Field({
                   label,
                   children,
               }: {
    label: string;
    children: React.ReactNode;
}) {
    return (
        <label className="block text-sm">
            <div className="mb-1 text-slate-500 dark:text-slate-400">
                {label}
            </div>
            {children}
        </label>
    );
}

function Card({
                  title,
                  children,
              }: {
    title: string;
    children: React.ReactNode;
}) {
    return (
        <div className="rounded-2xl border bg-white p-4 shadow-sm dark:border-slate-800 dark:bg-slate-900">
            <div className="mb-3 text-sm font-medium text-slate-700 dark:text-slate-200">
                {title}
            </div>
            <div className="grid grid-cols-2 gap-3 text-sm md:grid-cols-4">
                {children}
            </div>
        </div>
    );
}

function Snap({ k, v }: { k: string; v: unknown }) {
    return (
        <div className="rounded-xl border bg-slate-50 p-2 dark:border-slate-700 dark:bg-slate-800">
            <div className="text-[11px] uppercase tracking-wide text-slate-400 dark:text-slate-400">
                {k}
            </div>
            <div className="break-words font-medium">{String(v ?? "-")}</div>
        </div>
    );
}

function Banner({
                    icon,
                    text,
                    color,
                }: {
    icon: React.ReactNode;
    text: string;
    color: "slate" | "green" | "red";
}) {
    const tone =
        color === "green"
            ? "border-emerald-200 bg-emerald-50 text-emerald-700 dark:border-emerald-900 dark:bg-emerald-950 dark:text-emerald-200"
            : color === "red"
                ? "border-red-200 bg-red-50 text-red-700 dark:border-red-900 dark:bg-red-950 dark:text-red-200"
                : "border-slate-200 bg-slate-50 text-slate-700 dark:border-slate-800 dark:bg-slate-900 dark:text-slate-200";
    return (
        <div
            className={`flex items-center gap-2 rounded-xl border px-3 py-2 text-sm ${tone}`}
        >
            {icon} <span>{text}</span>
        </div>
    );
}

function errorMessage(e: unknown): string | undefined {
    if (typeof e === "string") return e;
    if (
        e &&
        typeof e === "object" &&
        "message" in e &&
        typeof (e as { message?: unknown }).message === "string"
    ) {
        return (e as { message: string }).message;
    }
    return undefined;
}
