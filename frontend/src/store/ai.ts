import { restApi } from './restApi'

// ─── Types ──────────────────────────────────────────────────────────────────

export interface AiChatRequest {
  conversationId?: number | null
  message: string
}

export interface AiAction {
  tool: string
  description: string
  success: boolean
}

export interface AiChatResponse {
  conversationId: number
  message: string
  actions: AiAction[]
}

export interface AiConversationSummary {
  id: number
  title: string | null
  updatedAt: string
}

export interface DisplayToolCall {
  tool: string
}

export interface DisplayMessage {
  role: string
  content: string
  toolCalls?: DisplayToolCall[] | null
}

// ─── API Endpoints ──────────────────────────────────────────────────────────

export const aiApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    aiChat: build.mutation<AiChatResponse, AiChatRequest>({
      query: (body) => ({
        url: 'ai/chat',
        method: 'POST',
        body,
      }),
      // AI actions may create presets, change effects, etc.
      invalidatesTags: [
        'Look',
        'LookList',
        'GroupActiveEffects',
        'FixtureEffects',
        'AiConversation',
      ],
    }),

    aiConversations: build.query<AiConversationSummary[], void>({
      // `current` rather than a threaded projectId: the panel follows whatever show is
      // loaded, and `POST ai/chat` (a live-runtime surface) can only ever mean that project.
      query: () => 'projects/current/ai/conversations',
      providesTags: ['AiConversation'],
    }),

    // A single-conversation read stood here. The panel keeps the live transcript in local
    // state and `aiChat` returns each reply, so re-reading a conversation never had a caller.

    deleteAiConversation: build.mutation<void, number>({
      query: (id) => ({
        url: `projects/current/ai/conversations/${id}`,
        method: 'DELETE',
      }),
      invalidatesTags: ['AiConversation'],
    }),
  }),
  overrideExisting: false,
})

export const {
  useAiChatMutation,
  useAiConversationsQuery,
  useDeleteAiConversationMutation,
} = aiApi
