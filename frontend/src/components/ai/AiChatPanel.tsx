import React, { useState, useRef, useEffect, useCallback } from 'react'
import { Send, Plus, Trash2, Loader2, MessageSquare, Sparkles } from 'lucide-react'
import Markdown from 'react-markdown'

import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
  SheetDescription,
} from '@/components/ui/sheet'
import { cn } from '@/lib/utils'
import {
  useAiChatMutation,
  useAiConversationsQuery,
  useDeleteAiConversationMutation,
  type AiAction,
  type DisplayMessage,
} from '@/store/ai'

interface AiChatPanelProps {
  isOpen: boolean
  onClose: () => void
}

export function AiChatPanel({ isOpen, onClose }: AiChatPanelProps) {
  const [conversationId, setConversationId] = useState<number | null>(null)
  const [messages, setMessages] = useState<DisplayMessage[]>([])
  const [pendingActions, setPendingActions] = useState<AiAction[]>([])
  const [inputValue, setInputValue] = useState('')
  const [showConversationList, setShowConversationList] = useState(false)

  const messagesEndRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLTextAreaElement>(null)

  const [sendChat, { isLoading: isSending }] = useAiChatMutation()
  const { data: conversations } = useAiConversationsQuery(undefined, { skip: !isOpen })
  const [deleteConversation] = useDeleteAiConversationMutation()

  const scrollToBottom = useCallback((instant = false) => {
    messagesEndRef.current?.scrollIntoView({
      behavior: instant ? 'instant' : 'smooth',
    })
  }, [])

  // Auto-scroll to bottom when messages change or thinking state changes
  useEffect(() => {
    scrollToBottom()
  }, [messages, isSending, scrollToBottom])

  // Scroll to bottom and focus input when panel opens
  useEffect(() => {
    if (isOpen) {
      // Wait for slide-in animation to begin rendering content
      setTimeout(() => {
        scrollToBottom(true)
        inputRef.current?.focus()
      }, 150)
    }
  }, [isOpen, scrollToBottom])

  const handleSend = useCallback(async () => {
    const text = inputValue.trim()
    if (!text || isSending) return

    setInputValue('')

    // Add user message to local state immediately
    setMessages((prev) => [...prev, { role: 'user', content: text }])

    try {
      const response = await sendChat({
        conversationId,
        message: text,
      }).unwrap()

      setConversationId(response.conversationId)
      setPendingActions(response.actions)

      // Add assistant message
      setMessages((prev) => [
        ...prev,
        {
          role: 'assistant',
          content: response.message,
          toolCalls: response.actions.map((a) => ({ tool: a.tool })),
        },
      ])
    } catch (err) {
      // Add error message
      setMessages((prev) => [
        ...prev,
        {
          role: 'assistant',
          content: `Error: ${err instanceof Error ? err.message : 'Failed to get response'}`,
        },
      ])
    }
  }, [inputValue, isSending, conversationId, sendChat])

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSend()
    }
  }

  const handleNewConversation = () => {
    setConversationId(null)
    setMessages([])
    setPendingActions([])
    setShowConversationList(false)
    setTimeout(() => inputRef.current?.focus(), 100)
  }

  const handleSelectConversation = async (id: number) => {
    // Load conversation from the list - we fetch the full detail
    try {
      const response = await fetch(`/api/rest/projects/current/ai/conversations/${id}`)
      if (response.ok) {
        const detail = await response.json()
        setConversationId(detail.id)
        setMessages(detail.messages)
        setPendingActions([])
        // Scroll to bottom after messages render
        setTimeout(() => scrollToBottom(true), 50)
      }
    } catch {
      // Ignore fetch errors
    }
    setShowConversationList(false)
  }

  const handleDeleteConversation = async (id: number, e: React.MouseEvent) => {
    e.stopPropagation()
    await deleteConversation(id)
    if (conversationId === id) {
      handleNewConversation()
    }
  }

  return (
    <Sheet open={isOpen} onOpenChange={(open) => !open && onClose()}>
      <SheetContent
        side="right"
        className="flex flex-col w-full sm:max-w-md p-0 gap-0"
      >
        <SheetHeader className="pl-4 pr-10 py-3 border-b flex-none">
          <div className="flex items-center justify-between">
            <SheetTitle className="text-base flex items-center gap-1.5">
              <Sparkles className="size-4" />
              Lux
            </SheetTitle>
            <div className="flex items-center gap-1">
              <Button
                variant="ghost"
                size="icon"
                className="size-8"
                onClick={() => setShowConversationList(!showConversationList)}
                title="Conversations"
              >
                <MessageSquare className="size-4" />
              </Button>
              <Button
                variant="ghost"
                size="icon"
                className="size-8"
                onClick={handleNewConversation}
                title="New conversation"
              >
                <Plus className="size-4" />
              </Button>
            </div>
          </div>
          <SheetDescription className="sr-only">
            AI lighting assistant powered by Claude
          </SheetDescription>
        </SheetHeader>

        {/* Conversation List Dropdown */}
        {showConversationList && (
          <div className="border-b bg-muted/50 max-h-48 overflow-y-auto flex-none">
            {conversations && conversations.length > 0 ? (
              conversations.map((conv) => (
                <div
                  key={conv.id}
                  className={cn(
                    'flex items-center gap-2 px-4 py-2 cursor-pointer hover:bg-muted text-sm',
                    conversationId === conv.id && 'bg-muted'
                  )}
                  onClick={() => handleSelectConversation(conv.id)}
                >
                  <span className="flex-1 truncate">
                    {conv.title || 'Untitled conversation'}
                  </span>
                  <Button
                    variant="ghost"
                    size="icon"
                    className="size-6 shrink-0 opacity-50 hover:opacity-100"
                    onClick={(e) => handleDeleteConversation(conv.id, e)}
                  >
                    <Trash2 className="size-3" />
                  </Button>
                </div>
              ))
            ) : (
              <div className="px-4 py-3 text-sm text-muted-foreground">
                No conversations yet
              </div>
            )}
          </div>
        )}

        {/* Messages Area */}
        <div className="flex-1 overflow-y-auto px-4 py-4 space-y-4 min-h-0">
          {messages.length === 0 && (
            <div className="flex flex-col items-center justify-center h-full text-muted-foreground text-sm gap-3">
              <Sparkles className="size-8 opacity-40" />
              <span>Ask Lux to create lighting effects!</span>
              <span className="text-xs text-center">
                e.g. &quot;Create a slow rainbow wash across all fixtures&quot;
              </span>
            </div>
          )}

          {messages.map((msg, idx) => (
            <MessageBubble
              key={idx}
              message={msg}
              actions={
                msg.role === 'assistant' && idx === messages.length - 1
                  ? pendingActions
                  : undefined
              }
            />
          ))}

          {isSending && (
            <div className="flex items-center gap-2 text-muted-foreground text-sm px-3 py-2">
              <Loader2 className="size-4 animate-spin" />
              <span>Thinking...</span>
            </div>
          )}

          <div ref={messagesEndRef} />
        </div>

        {/* Input Area */}
        <div className="border-t bg-background px-4 py-3 flex-none safe-area-pb">
          <div className="flex items-end gap-2">
            <textarea
              ref={inputRef}
              value={inputValue}
              onChange={(e) => setInputValue(e.target.value)}
              onKeyDown={handleKeyDown}
              placeholder="Ask about lighting..."
              disabled={isSending}
              rows={1}
              className={cn(
                'flex-1 resize-none rounded-md border bg-background px-3 py-2 text-sm',
                'placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring',
                'disabled:opacity-50 min-h-[38px] max-h-[120px]'
              )}
              style={{ height: 'auto' }}
              onInput={(e) => {
                const target = e.target as HTMLTextAreaElement
                target.style.height = 'auto'
                target.style.height = `${Math.min(target.scrollHeight, 120)}px`
              }}
            />
            <Button
              size="icon"
              onClick={handleSend}
              disabled={isSending || !inputValue.trim()}
              className="shrink-0"
            >
              <Send className="size-4" />
            </Button>
          </div>
        </div>
      </SheetContent>
    </Sheet>
  )
}

// ─── Message Bubble ─────────────────────────────────────────────────────────

interface MessageBubbleProps {
  message: DisplayMessage
  actions?: AiAction[]
}

function MessageBubble({ message, actions }: MessageBubbleProps) {
  const isUser = message.role === 'user'

  return (
    <div className={cn('flex', isUser ? 'justify-end' : 'justify-start')}>
      <div
        className={cn(
          'rounded-lg px-3 py-2 max-w-[85%] text-sm',
          isUser
            ? 'bg-primary text-primary-foreground whitespace-pre-wrap'
            : 'bg-muted text-foreground'
        )}
      >
        {isUser ? (
          message.content
        ) : (
          <div className="prose prose-sm dark:prose-invert max-w-none prose-p:my-1 prose-headings:my-2 prose-headings:text-sm prose-ul:my-1 prose-ol:my-1 prose-li:my-0 prose-pre:my-1 prose-pre:text-xs prose-code:text-xs prose-code:before:content-none prose-code:after:content-none">
            <Markdown>{message.content}</Markdown>
          </div>
        )}

        {/* Action badges */}
        {actions && actions.length > 0 && (
          <div className="flex flex-wrap gap-1 mt-2 pt-2 border-t border-foreground/10">
            {actions.map((action, idx) => (
              <Badge
                key={idx}
                variant={action.success ? 'secondary' : 'destructive'}
                className="text-[10px] px-1.5 py-0"
              >
                {formatToolName(action.tool)}
                {action.success ? ' \u2713' : ' \u2717'}
              </Badge>
            ))}
          </div>
        )}

        {/* Tool call indicators (from history, without full action details) */}
        {!actions && message.toolCalls && message.toolCalls.length > 0 && (
          <div className="flex flex-wrap gap-1 mt-2 pt-2 border-t border-foreground/10">
            {message.toolCalls.map((tc, idx) => (
              <Badge key={idx} variant="outline" className="text-[10px] px-1.5 py-0">
                {formatToolName(tc.tool)}
              </Badge>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}

function formatToolName(tool: string): string {
  return tool.replace(/_/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase())
}
