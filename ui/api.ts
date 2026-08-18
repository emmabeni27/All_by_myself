import { Rule } from "../types/Rule.ts";
import { BACKEND_URL } from "../utils/constants.ts";
import { SnippetOperations } from "../utils/snippetOperations.ts";
import { CreateSnippet, PaginatedSnippets, Snippet, SnippetData, UpdateSnippet } from "../utils/snippet.ts";
import { FileType } from "../types/FileType.ts";
import { GetTokenSilentlyOptions } from "@auth0/auth0-react";
import { StartExecutionResponse, ExecutionStatus, CancelExecutionRequest, SharedUser } from '../types/runner.ts';
import { formatPrintScriptCode } from "../utils/formatter.ts";

export class ApiSnippetOperations implements SnippetOperations {

    constructor(private getAccessToken: (options?: GetTokenSilentlyOptions) => Promise<string>) {}

    private async request<T>(endpoint: string, options?: RequestInit): Promise<T> {
        const url = `${BACKEND_URL}${endpoint}`;
        const token = await this.getAccessToken({
            authorizationParams: {
                audience: import.meta.env.VITE_AUTH0_AUDIENCE,
            }
        });

        const headers: HeadersInit = {
            'Content-Type': 'application/json',
            ...(token && { 'Authorization': `Bearer ${token}` }),
        };

        const defaultOptions: RequestInit = { headers };

        const response = await fetch(url, { ...defaultOptions, ...options });

        if (!response.ok) {
            const errorBody = await response.text();
            throw new Error(`HTTP error! status: ${response.status}, body: ${errorBody}`);
        }

        const text = await response.text();
        return text ? JSON.parse(text) : ({} as T);
    }

    // --- Rules ---

    async getFormatRules(language = "printscript"): Promise<Rule[]> {
        const rulesMap = await this.request<Record<string, string | number | boolean | null>>(`/api/v1/rules?task=formatting&language=${language}`);
        return Object.entries(rulesMap).map(([key, value]) => ({
            id: key,
            name: key,
            isActive: typeof value === 'boolean' ? value : true,
            value: typeof value === 'boolean' ? undefined : value,
        }));
    }

    modifyFormatRule(rules: Rule[], language = "printscript"): Promise<void> {
        const rulesMap = rules.reduce((acc, rule) => {
            if (rule.value === undefined || typeof rule.value === 'boolean') {
                acc[rule.name] = rule.isActive;
            } else {
                acc[rule.name] = rule.value;
            }
            return acc;
        }, {} as Record<string, string | number | boolean | null | undefined>);

        return this.request<void>('/api/v1/rules', {
            method: 'PUT',
            body: JSON.stringify({
                task: 'formatting',
                language,
                rules: rulesMap,
            }),
        });
    }

    async getLintingRules(language = "printscript"): Promise<Rule[]> {
        const rulesMap = await this.request<Record<string, string | number | boolean | null>>(`/api/v1/rules?task=linting&language=${language}`);
        return Object.entries(rulesMap).map(([key, value]) => ({
            id: key,
            name: key,
            isActive: typeof value === 'boolean' ? value : true,
            value: typeof value === 'boolean' ? undefined : value,
        }));
    }

    modifyLintingRule(rules: Rule[], language = "printscript"): Promise<void> {
        const rulesMap = rules.reduce((acc, rule) => {
            if (rule.value === undefined || typeof rule.value === 'boolean') {
                acc[rule.name] = rule.isActive;
            } else {
                acc[rule.name] = rule.value;
            }
            return acc;
        }, {} as Record<string, string | number | boolean | null | undefined>);

        return this.request<void>('/api/v1/rules', {
            method: 'PUT',
            body: JSON.stringify({
                task: 'linting',
                language,
                rules: rulesMap,
            }),
        });
    }

    // --- Snippets ---

    async listSnippetDescriptors(page: number, pageSize: number, snippetName?: string): Promise<PaginatedSnippets> {
        const params = new URLSearchParams({
            page: String(page),
            pageSize: String(pageSize),
        });
        if (snippetName) {
            params.append('name', snippetName);
        }

        const snippetsMap = await this.request<Record<string, { name: string; language: string; permission: string }>>(`/api/v1/snippets?${params.toString()}`);

        const snippetsArray: Snippet[] = Object.entries(snippetsMap).map(([id, details]) => ({
            id: id,
            name: details.name,
            language: details.language,
            author: details.permission, // Using role as author for now
            content: '', // This endpoint does not provide content
            extension: '', // This endpoint does not provide extension
            compliance: 'pending', // Default value
        }));

        return {
            page: 1, // The API doesn't return pagination data, so mocking it.
            page_size: snippetsArray.length,
            count: snippetsArray.length,
            snippets: snippetsArray,
        };
    }

    createSnippet(createSnippet: CreateSnippet, userId?: string): Promise<void> {
        return this.request<void>(`/api/v1/snippets/${createSnippet.id}`, {
            method: 'PUT',
            body: JSON.stringify({
                userId: userId ?? '',
                name: createSnippet.name,
                language: createSnippet.language,
            }),
        });
    }

    deleteSnippet(id: string): Promise<string> {
        return this.request<string>(`/api/v1/snippets/${id}`, {
            method: 'DELETE',
        });
    }

    shareSnippet(snippetId: string, userId: string): Promise<Snippet> {
        return this.request<Snippet>(`/api/v1/snippets/${snippetId}/permission`, {
            method: 'PUT',
            body: JSON.stringify({ userId }),
        });
    }

    getSharedUsers(snippetId: string): Promise<SharedUser[]> {
        return this.request<SharedUser[]>(`/api/v1/snippets/${snippetId}/permission`);
    }

    // Métodos no implementados (placeholders)
    getFileTypes(): Promise<FileType[]> {
        return Promise.resolve([{ language: "printscript", extension: "ps", version: "1.1" }]);
    }
    getTestCases(snippetId: string): Promise<string[]> {
        return this.request<string[]>(`/api/v1/snippets/${snippetId}/tests`);
    }
    removeTestCase(_id: string): Promise<string> {
        throw new Error("Method not implemented.");
    }
    async formatSnippet(snippet: string): Promise<string> {
        try {
            const rules = await this.getFormatRules("printscript");
            return formatPrintScriptCode(snippet, rules);
        } catch (_error) {
            return formatPrintScriptCode(snippet, []);
        }
    }
    getSnippetData(id: string): Promise<SnippetData> {
        return this.request<SnippetData>(`/api/v1/snippets/${id}`);
    }
    updateSnippetById(_id: string, _updateSnippet: UpdateSnippet): Promise<Snippet> {
        throw new Error("Method not implemented.");
    }

    // --- Test & Execution ---
    async startExecution(snippetId: string, environment: Record<string, string>, version: string): Promise<StartExecutionResponse> {
        return this.request<StartExecutionResponse>(`/api/v1/snippets/${snippetId}/execution`, {
            method: 'POST',
            body: JSON.stringify({ environment, version }),
        });
    }

    sendInput(snippetId: string, input: string): Promise<void> {
        return this.request<void>(`/api/v1/snippets/${snippetId}/execution/input`, {
            method: 'POST',
            body: JSON.stringify({ input }),
        });
    }

    cancelExecution(snippetId: string, userId: string): Promise<void> {
        return this.request<void>(`/api/v1/snippets/${snippetId}/execution`, {
            method: 'DELETE',
            body: JSON.stringify({ userId } as CancelExecutionRequest),
        });
    }

    getExecutionStatus(snippetId: string, _executionId: string): Promise<ExecutionStatus> {
        // The App's endpoint is /api/v1/snippets/{snippetId}/run/status
        return this.request<ExecutionStatus>(`/api/v1/snippets/${snippetId}/run/status`);
    }

    registerUser(): Promise<void> {
        return this.request<void>('/api/v1/users', {
            method: 'PUT',
        });
    }
}