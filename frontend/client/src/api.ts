export type IngestionState = 'QUEUED' | 'INGESTING' | 'COGNIFYING' | 'COMPLETED' | 'FAILED'
export type EvaluationState = 'QUEUED' | 'EVALUATING' | 'COMPLETED' | 'FAILED'

export interface IngestionStatus {
  id: string
  repositoryUrl: string
  datasetName: string
  status: IngestionState
  createdAt: string
  updatedAt: string
  error: string | null
}

export interface EvaluationProgress {
  evaluationId: string
  ingestionId: string
  status: EvaluationState
  stage: string
  progress: number
  message: string
  updatedAt: string
}

export interface EvaluationStatus {
  id: string
  ingestionId: string
  repositoryUrl: string
  status: EvaluationState
  createdAt: string
  updatedAt: string
  error: string | null
}

export interface Citation {
  evidenceId: string
  filePath: string
  startLine: number
  endLine: number
  excerpt: string
}

export interface DimensionScore {
  dimension: string
  score: number
  rationale: string
  citations: Citation[]
}

export interface EvaluationReport {
  id: string
  ingestionId: string
  repositoryUrl: string
  overallScore: number
  summary: string
  dimensions: DimensionScore[]
  createdAt: string
  completedAt: string
}

export class ApiError extends Error {
  readonly status: number

  constructor(message: string, status: number) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  let response: Response
  try {
    response = await fetch(url, {
      ...init,
      headers: { Accept: 'application/json', ...init?.headers },
    })
  } catch {
    throw new ApiError('CampusCode could not be reached. Check that the backend is running and try again.', 0)
  }

  if (!response.ok) {
    let message = describeHttpFailure(response.status, `Request failed (${response.status})`)
    try {
      const body = (await response.json()) as { error?: string; detail?: string }
      message = body.detail ? `${body.error ?? 'Request failed'}: ${body.detail}` : body.error ?? message
    } catch {
      // Keep the status based message when the server does not return JSON.
    }
    throw new ApiError(message, response.status)
  }

  try {
    return (await response.json()) as T
  } catch {
    throw new ApiError('CampusCode returned an unreadable response. Please try again.', response.status)
  }
}

export function createIngestion(input: { repositoryUrl: string; notificationEmail?: string }) {
  return request<IngestionStatus>('/api/repositories/ingestions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  })
}

export function getIngestion(id: string) {
  return request<IngestionStatus>(`/api/repositories/ingestions/${encodeURIComponent(id)}`)
}

export function getReport(ingestionId: string) {
  return request<EvaluationReport>(`/api/repositories/ingestions/${encodeURIComponent(ingestionId)}/report`)
}

export function getEvaluationStatus(evaluationId: string) {
  return request<EvaluationStatus>(`/api/repositories/evaluations/${encodeURIComponent(evaluationId)}`)
}

export type EvaluationStreamEvent =
  | { type: 'progress'; data: EvaluationProgress }
  | { type: 'completed'; data: EvaluationReport }
  | { type: 'failed'; data: EvaluationStatus }

async function readEventStream(response: Response, onEvent: (event: EvaluationStreamEvent) => void) {
  if (!response.body) throw new Error('This browser could not open the evaluation stream.')
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let terminal = false

  const dispatchFrames = () => {
    const frames = buffer.split(/\r?\n\r?\n/)
    buffer = frames.pop() ?? ''
    for (const frame of frames) {
      let eventName = 'message'
      const data: string[] = []
      for (const line of frame.split(/\r?\n/)) {
        if (line.startsWith('event:')) eventName = line.slice(6).trim()
        if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
      }
      if (!data.length) continue
      if (eventName !== 'progress' && eventName !== 'completed' && eventName !== 'failed') continue
      let parsed: unknown
      try {
        parsed = JSON.parse(data.join('\n'))
      } catch {
        throw new Error('The evaluation stream returned an invalid event.')
      }
      onEvent({ type: eventName, data: parsed } as EvaluationStreamEvent)
      if (eventName === 'completed' || eventName === 'failed') terminal = true
    }
  }

  while (!terminal) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    dispatchFrames()
  }
  buffer += decoder.decode()
  if (buffer.trim() && !terminal) {
    // Process a final frame even if a server closed without the usual blank line.
    buffer += '\n\n'
    dispatchFrames()
  }
  await reader.cancel().catch(() => undefined)
  return terminal
}

export async function streamEvaluation(
  ingestionId: string,
  signal: AbortSignal,
  onEvent: (event: EvaluationStreamEvent) => void,
  onReconnect: (attempt: number) => void,
) {
  let attempt = 0
  while (!signal.aborted && attempt < 6) {
    try {
      const response = await fetch(`/api/repositories/ingestions/${encodeURIComponent(ingestionId)}/evaluations/stream`, {
        method: 'POST',
        headers: { Accept: 'text/event-stream' },
        signal,
      })
      if (!response.ok) {
        let message = describeHttpFailure(response.status, `Evaluation could not start (${response.status})`)
        try {
          const body = (await response.json()) as { error?: string; detail?: string }
          message = body.detail ? `${body.error ?? message}: ${body.detail}` : body.error ?? message
        } catch {
          // Preserve the fallback message for a non-JSON error response.
        }
        throw new ApiError(message, response.status)
      }
      if (await readEventStream(response, onEvent)) return
      attempt += 1
      onReconnect(attempt)
    } catch (error) {
      if (signal.aborted) return
      if (error instanceof ApiError) throw error
      attempt += 1
      onReconnect(attempt)
    }
    if (!signal.aborted && attempt < 6) {
      await new Promise<void>((resolve) => {
        const timeout = window.setTimeout(resolve, Math.min(1000 * 2 ** (attempt - 1), 8000))
        signal.addEventListener('abort', () => {
          window.clearTimeout(timeout)
          resolve()
        }, { once: true })
      })
    }
  }
  if (!signal.aborted) throw new Error('The progress connection was interrupted. Your server-side job may still be running; reconnect to resume it.')
}

function describeHttpFailure(status: number, fallback: string) {
  if (status === 502 || status === 503) return 'The CampusCode API is not responding. Start the Spring Boot backend and try again.'
  if (status === 504) return 'The CampusCode API took too long to respond. Check the backend and try again.'
  return fallback
}
