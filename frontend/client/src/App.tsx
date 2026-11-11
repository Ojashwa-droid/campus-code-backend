import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useRef, useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, Route, Routes, useLocation, useNavigate, useParams } from 'react-router-dom'
import { z } from 'zod'
import {
  ApiError,
  createIngestion,
  getEvaluationStatus,
  getIngestion,
  getReport,
  streamEvaluation,
} from './api'
import type { EvaluationProgress, EvaluationReport, IngestionState } from './api'
import './App.css'

const rubric = [
  { name: 'Architecture', note: 'Entry points, module boundaries, responsibilities, and dependencies.' },
  { name: 'Maintainability', note: 'Naming, separation of concerns, duplication, complexity, and abstractions.' },
  { name: 'Testing', note: 'Automated tests, behavior, edge cases, and test configuration.' },
  { name: 'Reliability', note: 'Error handling, validation, resilience, and resource management.' },
  { name: 'Security', note: 'Authentication, input validation, secret handling, and sensitive code.' },
  { name: 'Performance & scalability', note: 'Algorithms, data access, concurrency, caching, and resource use.' },
]

function isSupportedRepository(value: string) {
  try {
    const url = new URL(value)
    const segments = url.pathname.split('/').filter(Boolean)
    return url.protocol === 'https:'
      && url.hostname.toLowerCase() === 'github.com'
      && !url.port
      && !url.username
      && !url.password
      && !url.search
      && !url.hash
      && segments.length === 2
      && segments.every((segment) => /^[A-Za-z0-9_.-]+$/.test(segment) && segment !== '.' && segment !== '..')
  } catch {
    return false
  }
}

const formSchema = z.object({
  repositoryUrl: z.string().trim().refine(isSupportedRepository, 'Enter a public GitHub repository URL, like https://github.com/owner/repository.'),
  notificationEmail: z.string().trim().refine((value) => !value || z.email().safeParse(value).success, 'Enter a valid email address or leave this field blank.'),
})
type FormValues = z.infer<typeof formSchema>

function App() {
  const location = useLocation()

  useEffect(() => {
    if (location.pathname !== '/' || !location.hash) return
    const targetId = location.hash.slice(1)
    const scroll = window.setTimeout(() => document.getElementById(targetId)?.scrollIntoView({ behavior: 'smooth' }), 0)
    return () => window.clearTimeout(scroll)
  }, [location.pathname, location.hash])

  return (
    <div className="app-shell">
      <header className="topbar">
        <Link className="brand" to="/" aria-label="CampusCode home">
          <span className="brand-mark" aria-hidden="true">&lt;/&gt;</span>
          <span>CampusCode</span>
        </Link>
        <nav className="site-nav" aria-label="Main navigation">
          <Link className="nav-link" to="/#product">Product</Link>
          <Link className="nav-link" to="/#rubric">Evaluation rubric</Link>
        </nav>
        <Link className="nav-cta" to="/#repository-form">Evaluate a repository</Link>
      </header>
      <main>
        <Routes>
          <Route path="/" element={<SubmitPage />} />
          <Route path="/ingestions/:ingestionId" element={<IngestionPage />} />
          <Route path="/ingestions/:ingestionId/report" element={<ReportPage />} />
          <Route path="*" element={<NotFoundPage />} />
        </Routes>
      </main>
      <footer className="site-footer">
        <span className="footer-info" aria-hidden="true">i</span>
        <p>The report is repository evidence for human review, not an automated hiring decision.</p>
      </footer>
    </div>
  )
}

function SubmitPage() {
  const navigate = useNavigate()
  const [activePreview, setActivePreview] = useState<'dimensions' | 'evidence'>('dimensions')
  const form = useForm<FormValues>({
    resolver: zodResolver(formSchema),
    defaultValues: { repositoryUrl: '', notificationEmail: '' },
  })
  const createJob = useMutation({ mutationFn: createIngestion })
  const submit = form.handleSubmit((values) => {
    createJob.mutate({
      repositoryUrl: values.repositoryUrl.trim(),
      ...(values.notificationEmail.trim() ? { notificationEmail: values.notificationEmail.trim() } : {}),
    }, {
      onSuccess: (job) => navigate(`/ingestions/${job.id}`),
    })
  })

  return (
    <>
      <section className="hero-section" id="product" aria-labelledby="landing-title">
        <div className="hero-inner">
          <div className="hero-copy">
            <p className="eyebrow">REPOSITORY ENGINEERING REVIEW</p>
            <h1 id="landing-title">See the engineering behind the repository.</h1>
            <p className="hero-lede">A cited engineering scorecard across six dimensions, designed for human review.</p>
            <a className="hero-cta" href="#repository-form">Evaluate a repository <span aria-hidden="true">→</span></a>
            <p className="hero-note">For public GitHub repositories · Evidence for review, not an automatic hiring decision</p>
          </div>
          <div className="process-flow" aria-label="Repository review process">
            <ProcessStep symbol="</>" title="Repository" />
            <span className="process-arrow" aria-hidden="true">→</span>
            <ProcessStep symbol="⌕" title="Evidence" />
            <span className="process-arrow" aria-hidden="true">→</span>
            <ProcessStep symbol="✓" title="Reviewable scorecard" />
          </div>
          <a className="hero-scroll-cue" href="#preview" aria-label="Scroll to the scorecard preview">⌄</a>
        </div>
      </section>

      <section className="preview-section" id="preview" aria-labelledby="preview-title">
        <div className="preview-inner">
          <p className="preview-kicker">A SCORECARD YOU CAN TRACE</p>
          <h2 id="preview-title" className="visually-hidden">A scorecard you can trace</h2>
          <div className="preview-switch" role="group" aria-label="Scorecard preview details">
            <button type="button" aria-pressed={activePreview === 'dimensions'} onClick={() => setActivePreview('dimensions')}>Six dimensions</button>
            <button type="button" aria-pressed={activePreview === 'evidence'} onClick={() => setActivePreview('evidence')}>Evidence trail</button>
          </div>

          <div className="preview-window" aria-live="polite">
            <div className="preview-window-bar"><span className="window-dots" aria-hidden="true"><i /><i /><i /></span><span className="preview-window-title">CampusCode <span>/</span> Engineering scorecard</span></div>
            {activePreview === 'dimensions' ? <DimensionPreview /> : <EvidencePreview />}
          </div>
        </div>
      </section>

      <section className="submission-section" id="repository-form" aria-labelledby="submission-title">
        <div className="submission-inner">
          <div className="submission-intro">
            <p className="eyebrow">START A REPOSITORY REVIEW</p>
            <h2 id="submission-title">Begin with the source.</h2>
            <p>Submit a public GitHub repository. CampusCode will prepare it for review, then you can choose when to run the scorecard.</p>
          </div>
          <form className="submission-form" onSubmit={submit} noValidate>
            <div className="field-group">
              <label htmlFor="repositoryUrl">GitHub repository URL</label>
              <input
                id="repositoryUrl"
                type="url"
                autoComplete="url"
                placeholder="https://github.com/owner/repository"
                aria-invalid={Boolean(form.formState.errors.repositoryUrl)}
                aria-describedby={form.formState.errors.repositoryUrl ? 'repository-error' : 'repository-hint'}
                {...form.register('repositoryUrl')}
              />
              {form.formState.errors.repositoryUrl
                ? <p className="field-error" id="repository-error" role="alert">{form.formState.errors.repositoryUrl.message}</p>
                : <p className="field-hint" id="repository-hint">Public GitHub repositories only.</p>}
            </div>
            <div className="field-group email-group">
              <label htmlFor="notificationEmail">Notification email <span>(optional)</span></label>
              <input
                id="notificationEmail"
                type="email"
                autoComplete="email"
                placeholder="you@example.com"
                aria-invalid={Boolean(form.formState.errors.notificationEmail)}
                aria-describedby={form.formState.errors.notificationEmail ? 'email-error' : 'email-hint'}
                {...form.register('notificationEmail')}
              />
              {form.formState.errors.notificationEmail
                ? <p className="field-error" id="email-error" role="alert">{form.formState.errors.notificationEmail.message}</p>
                : <p className="field-hint" id="email-hint">An email is sent only after the report is ready.</p>}
            </div>
            {createJob.isError && <ErrorNotice error={createJob.error} />}
            <button className="primary-button submit-button" type="submit" disabled={createJob.isPending}>
              {createJob.isPending ? <><span className="spinner" aria-hidden="true" /> Starting review…</> : 'Start repository review'}
            </button>
            <p className="repeat-note">Submitting a repository again may reopen its existing job.</p>
          </form>
        </div>
      </section>
    </>
  )
}

function ProcessStep({ symbol, title }: { symbol: string; title: string }) {
  return <div className="process-step"><span className="process-symbol" aria-hidden="true">{symbol}</span><span>{title}</span></div>
}

function DimensionPreview() {
  return <div className="preview-content">
    <div className="preview-rail">
      {rubric.map((item, index) => <div className={`preview-rail-item ${index === 0 ? 'selected' : ''}`} key={item.name}><span className="rail-index">0{index + 1}</span><span>{item.name}</span></div>)}
    </div>
    <div className="preview-detail" id="rubric">
      <p className="detail-eyebrow">EQUAL-WEIGHT RUBRIC · 0–5 PER DIMENSION</p>
      <h3>Six dimensions, one clear review.</h3>
      <p className="detail-lede">Each area is assessed independently and supported by retrieved repository evidence.</p>
      <ul className="dimension-details">
        {rubric.map((item) => <li key={item.name}><span className="detail-check" aria-hidden="true">✓</span><p><strong>{item.name}</strong><span>{item.note}</span></p></li>)}
      </ul>
    </div>
  </div>
}

function EvidencePreview() {
  return <div className="preview-content evidence-preview">
    <div className="preview-rail evidence-rail" aria-label="Citation fields">
      <div className="preview-rail-item selected"><span className="rail-index">01</span><span>Source location</span></div>
      <div className="preview-rail-item"><span className="rail-index">02</span><span>Line range</span></div>
      <div className="preview-rail-item"><span className="rail-index">03</span><span>Excerpt</span></div>
    </div>
    <div className="preview-detail">
      <p className="detail-eyebrow">CITATION STRUCTURE</p>
      <h3>Follow every finding to its evidence.</h3>
      <p className="detail-lede">Reports include source paths, cited lines, and excerpts that reviewers can inspect against the repository.</p>
      <div className="evidence-fields">
        <div><span className="field-symbol" aria-hidden="true">⌘</span><p><strong>File path</strong><span>Identifies the repository source.</span></p></div>
        <div><span className="field-symbol" aria-hidden="true">↕</span><p><strong>Start and end lines</strong><span>Line ranges are model-supplied; verify them in source.</span></p></div>
        <div><span className="field-symbol" aria-hidden="true">“ ”</span><p><strong>Excerpt</strong><span>Checked against the retrieved passage and source path.</span></p></div>
      </div>
    </div>
  </div>
}

function IngestionPage() {
  const { ingestionId = '' } = useParams()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [progress, setProgress] = useState<EvaluationProgress | null>(null)
  const [streamError, setStreamError] = useState('')
  const [connecting, setConnecting] = useState(false)
  const controllerRef = useRef<AbortController | null>(null)
  const startedRef = useRef(false)

  const ingestionQuery = useQuery({
    queryKey: ['ingestion', ingestionId],
    queryFn: () => getIngestion(ingestionId),
    enabled: Boolean(ingestionId),
    refetchInterval: (query) => {
      const status = query.state.data?.status
      return status && ['QUEUED', 'INGESTING', 'COGNIFYING'].includes(status) ? 3000 : false
    },
  })
  const evaluationId = localStorage.getItem(`campuscode:evaluation:${ingestionId}`) ?? ''
  const evaluationQuery = useQuery({
    queryKey: ['evaluation', evaluationId],
    queryFn: () => getEvaluationStatus(evaluationId),
    enabled: Boolean(evaluationId),
    refetchInterval: (query) => query.state.data?.status === 'QUEUED' || query.state.data?.status === 'EVALUATING' ? 3000 : false,
  })
  const evaluationIsActive = evaluationQuery.data?.status === 'QUEUED' || evaluationQuery.data?.status === 'EVALUATING'

  const runEvaluation = useCallback(async () => {
    if (startedRef.current || !ingestionId) return
    startedRef.current = true
    setStreamError('')
    setConnecting(true)
    localStorage.setItem(`campuscode:evaluation-started:${ingestionId}`, '1')
    const controller = new AbortController()
    controllerRef.current = controller
    try {
      await streamEvaluation(ingestionId, controller.signal, (event) => {
        if (event.type === 'progress') {
          setProgress(event.data)
          localStorage.setItem(`campuscode:evaluation:${ingestionId}`, event.data.evaluationId)
          void queryClient.invalidateQueries({ queryKey: ['evaluation', event.data.evaluationId] })
        } else if (event.type === 'completed') {
          localStorage.setItem(`campuscode:evaluation:${ingestionId}`, event.data.id)
          localStorage.removeItem(`campuscode:evaluation-started:${ingestionId}`)
          queryClient.setQueryData(['report', ingestionId], event.data)
          void queryClient.invalidateQueries({ queryKey: ['evaluation', event.data.id] })
          navigate(`/ingestions/${ingestionId}/report`)
        } else {
          localStorage.setItem(`campuscode:evaluation:${ingestionId}`, event.data.id)
          localStorage.removeItem(`campuscode:evaluation-started:${ingestionId}`)
          setStreamError(event.data.error || 'The evaluation did not complete. You can retry it.')
          void queryClient.invalidateQueries({ queryKey: ['evaluation', event.data.id] })
        }
      }, (attempt) => {
        setConnecting(true)
        setProgress((previous) => previous ? { ...previous, message: `Connection interrupted. Reconnecting (${attempt}/6)…` } : null)
      })
    } catch (error) {
      if (!controller.signal.aborted) setStreamError(error instanceof Error ? error.message : 'The evaluation connection failed.')
    } finally {
      if (controllerRef.current === controller) {
        controllerRef.current = null
        startedRef.current = false
        setConnecting(false)
      }
    }
  }, [ingestionId, navigate, queryClient])

  useEffect(() => () => controllerRef.current?.abort(), [runEvaluation])

  useEffect(() => {
    const recover = window.setTimeout(() => {
      const shouldResume = localStorage.getItem(`campuscode:evaluation-started:${ingestionId}`) === '1'
      if (ingestionQuery.data?.status === 'COMPLETED' && shouldResume && !startedRef.current) void runEvaluation()
    }, 0)
    return () => window.clearTimeout(recover)
  }, [ingestionQuery.data?.status, ingestionId, runEvaluation])

  const ingestion = ingestionQuery.data
  if (ingestionQuery.isPending) return <LoadingState label="Loading repository status…" />
  if (ingestionQuery.isError) return <PageError title="We couldn’t load this repository" error={ingestionQuery.error} />
  if (!ingestion) return <PageError title="We couldn’t load this repository" error="The API returned an empty ingestion status." />

  const ingestSteps: Array<{ state: IngestionState; title: string; description: string }> = [
    { state: 'QUEUED', title: 'Queued', description: 'Waiting for an ingestion worker.' },
    { state: 'INGESTING', title: 'Reading repository', description: 'Fetching the public repository contents.' },
    { state: 'COGNIFYING', title: 'Building repository memory', description: 'Indexing code and documentation for evidence retrieval.' },
    { state: 'COMPLETED', title: 'Repository ready', description: 'The repository is ready for evaluation.' },
  ]
  const activeIngestion = ['QUEUED', 'INGESTING', 'COGNIFYING'].includes(ingestion.status)
  const stepPosition = ingestion.status === 'FAILED' ? -1 : ingestSteps.findIndex((step) => step.state === ingestion.status)
  const isEvaluationFailed = evaluationQuery.data?.status === 'FAILED'
  const evaluationDone = evaluationQuery.data?.status === 'COMPLETED'

  return (
    <section className="work-page" aria-labelledby="job-title">
      <div className="work-heading">
        <Link to="/" className="back-link">← New repository</Link>
        <p className="eyebrow">REPOSITORY REVIEW <span>/</span> JOB STATUS</p>
        <h1 id="job-title">Review progress</h1>
        <a className="repository-link" href={ingestion.repositoryUrl} target="_blank" rel="noreferrer">{ingestion.repositoryUrl}<span aria-hidden="true">↗</span></a>
        <p className="job-meta">Submitted {formatDate(ingestion.createdAt)} · Updated {formatDate(ingestion.updatedAt)}</p>
      </div>

      {ingestion.status === 'FAILED' ? (
        <div className="failure-panel" role="alert"><span className="state-dot failed" /><div><h2>Repository ingestion failed</h2><p>{ingestion.error || 'The repository could not be processed.'}</p><Link className="secondary-button" to="/">Try another submission</Link></div></div>
      ) : (
        <div className="status-layout">
          <div className="status-card">
            <div className="section-title-row"><div><p className="eyebrow">INGESTION</p><h2>{activeIngestion ? 'Preparing the repository' : 'Repository ready'}</h2></div><StatusPill state={ingestion.status} /></div>
            <ol className="timeline">
              {ingestSteps.map((step, index) => {
                const isComplete = ingestion.status === 'COMPLETED' || index < stepPosition
                const isCurrent = ingestion.status !== 'COMPLETED' && index === stepPosition
                return <li key={step.state} className={`timeline-item ${isComplete ? 'complete' : ''} ${isCurrent ? 'current' : ''}`}>
                  <span className="timeline-marker" aria-hidden="true">{isComplete ? '✓' : index + 1}</span>
                  <div><strong>{step.title}</strong><p>{step.description}</p></div>
                </li>
              })}
            </ol>
            {activeIngestion && <p className="live-message" role="status"><span className="pulse-dot" /> Status updates automatically every few seconds.</p>}
          </div>

          {ingestion.status === 'COMPLETED' && <div className="status-card evaluation-card">
            <div className="section-title-row"><div><p className="eyebrow">ENGINEERING SCORECARD</p><h2>{evaluationDone ? 'Report ready' : isEvaluationFailed ? 'Evaluation needs another try' : 'Generate an evaluation'}</h2></div><StatusPill state={evaluationQuery.data?.status ?? (connecting ? 'EVALUATING' : 'READY')} /></div>
            <p className="card-description">The scorecard reviews six engineering dimensions and links each finding to repository evidence. Starting the evaluation may take a few minutes.</p>
            {progress && <div className="progress-box" role="status" aria-live="polite"><div className="progress-heading"><strong>{progress.message}</strong><span>{progress.progress}%</span></div><div className="progress-track"><span style={{ width: `${Math.min(100, Math.max(0, progress.progress))}%` }} /></div><small>{formatStage(progress.stage)}</small></div>}
            {evaluationIsActive && !connecting && <p className="live-message" role="status">An evaluation is already running. Reconnect to follow its progress.</p>}
            {isEvaluationFailed && <p className="field-error failure-copy" role="alert">{streamError || evaluationQuery.data?.error || 'The evaluation failed.'}</p>}
            {streamError && !isEvaluationFailed && <ErrorNotice error={streamError} />}
            {evaluationDone && <p className="success-note">This evaluation is complete. Reopen its saved report or run again to load the result.</p>}
            <div className="evaluation-actions">
              {evaluationDone && <Link className="secondary-button" to={`/ingestions/${ingestionId}/report`}>Open saved report</Link>}
              <button className="primary-button" type="button" onClick={runEvaluation} disabled={connecting}>
                {connecting ? <><span className="spinner" aria-hidden="true" /> {progress ? 'Following evaluation…' : 'Connecting…'}</> : isEvaluationFailed ? 'Retry evaluation' : evaluationIsActive ? 'Reconnect to evaluation' : evaluationDone ? 'Open evaluation again' : 'Run evaluation'}
              </button>
            </div>
            <p className="small-truth">Closing this page does not cancel server-side processing.</p>
          </div>}
        </div>
      )}
    </section>
  )
}

function ReportPage() {
  const { ingestionId = '' } = useParams()
  const reportQuery = useQuery({ queryKey: ['report', ingestionId], queryFn: () => getReport(ingestionId), enabled: Boolean(ingestionId), retry: false })
  if (reportQuery.isPending) return <LoadingState label="Loading saved scorecard…" />
  if (reportQuery.isError) {
    const notReady = reportQuery.error instanceof ApiError && (reportQuery.error.status === 400 || reportQuery.error.status === 409)
    return <section className="work-page"><Link to={`/ingestions/${ingestionId}`} className="back-link">← Back to repository status</Link><div className="report-unavailable"><span className="state-dot pending" /><div><h1>{notReady ? 'Report not ready yet' : 'We couldn’t load the report'}</h1><p>{notReady ? 'The saved scorecard is available after evaluation completes.' : errorMessage(reportQuery.error)}</p><Link className="primary-button link-button" to={`/ingestions/${ingestionId}`}>Return to job status</Link></div></div></section>
  }
  return <ReportView report={reportQuery.data} />
}

function ReportView({ report }: { report: EvaluationReport }) {
  const orderedDimensions = [...report.dimensions].sort((a, b) => {
    const aIndex = rubric.findIndex((item) => item.name.toLowerCase() === a.dimension.toLowerCase().replace('/', ' & '))
    const bIndex = rubric.findIndex((item) => item.name.toLowerCase() === b.dimension.toLowerCase().replace('/', ' & '))
    return (aIndex === -1 ? rubric.length : aIndex) - (bIndex === -1 ? rubric.length : bIndex)
  })
  return (
    <section className="report-page" aria-labelledby="report-title">
      <div className="report-heading">
        <Link to={`/ingestions/${report.ingestionId}`} className="back-link">← Back to repository status</Link>
        <p className="eyebrow">REPOSITORY REVIEW <span>/</span> EVALUATION REPORT</p>
        <h1 id="report-title">Engineering scorecard</h1>
        <a className="repository-link" href={report.repositoryUrl} target="_blank" rel="noreferrer">{report.repositoryUrl}<span aria-hidden="true">↗</span></a>
      </div>

      <div className="report-summary status-card">
        <div className="score-orb" aria-label={`Overall score ${report.overallScore} out of 100`}><strong>{report.overallScore}</strong><span>/100</span></div>
        <div className="summary-copy"><p className="eyebrow">OVERALL SCORE · EQUAL-WEIGHT RUBRIC</p><h2>Evidence across six dimensions</h2><p>{report.summary}</p><p className="score-explainer">Each dimension is scored from 0 to 5. The overall score is the average normalized to 100.</p></div>
      </div>
      <div className="evidence-banner"><span className="info-icon" aria-hidden="true">i</span><p>This score reflects evidence available in the indexed repository. A low or zero score may mean evidence was not found; it is not a measure of a person’s overall ability or a hiring recommendation.</p></div>

      <div className="dimension-list">
        {orderedDimensions.map((dimension) => {
          const noEvidence = dimension.score === 0 && dimension.citations.length === 0
          return <article className="dimension-card" key={dimension.dimension}>
            <div className="dimension-top"><div><p className="dimension-label">ENGINEERING DIMENSION</p><h2>{displayDimension(dimension.dimension)}</h2></div><div className="dimension-score"><strong>{dimension.score}</strong><span>/5</span></div></div>
            <div className="score-meter" aria-hidden="true"><span style={{ width: `${Math.min(100, Math.max(0, dimension.score * 20))}%` }} /></div>
            {noEvidence && <p className="insufficient-label"><span className="state-dot pending" /> Insufficient verifiable evidence</p>}
            <p className="dimension-rationale">{dimension.rationale}</p>
            {dimension.citations.length > 0 ? <div className="citations"><h3>{dimension.citations.length === 1 ? 'Repository citation' : `${dimension.citations.length} repository citations`}</h3>
              {dimension.citations.map((citation, index) => <details className="citation-item" key={`${citation.evidenceId}-${index}`}>
                <summary><span className="file-icon" aria-hidden="true">⌁</span><span className="citation-path">{citation.filePath}</span><span className="line-range">L{citation.startLine}–{citation.endLine}</span></summary>
                <div className="citation-body"><p className="citation-note">Line ranges are model-supplied and should be verified against the source file.</p><pre><code>{citation.excerpt}</code></pre></div>
              </details>)}
            </div> : <p className="no-citation">No verifiable repository citation was returned for this dimension.</p>}
          </article>
        })}
      </div>
    </section>
  )
}

function StatusPill({ state }: { state: string }) {
  const readable: Record<string, string> = { QUEUED: 'Queued', INGESTING: 'Ingesting', COGNIFYING: 'Indexing', COMPLETED: 'Completed', FAILED: 'Failed', EVALUATING: 'Evaluating', READY: 'Ready' }
  return <span className={`status-pill status-${state.toLowerCase()}`}><span className="state-dot" />{readable[state] ?? state}</span>
}

function ErrorNotice({ error }: { error: unknown }) {
  return <div className="error-notice" role="alert"><strong>Something went wrong</strong><span>{errorMessage(error)}</span></div>
}

function PageError({ title, error }: { title: string; error: unknown }) {
  return <section className="work-page"><div className="report-unavailable"><span className="state-dot failed" /><div><h1>{title}</h1><p>{errorMessage(error)}</p><Link className="primary-button link-button" to="/">Back to CampusCode</Link></div></div></section>
}

function LoadingState({ label }: { label: string }) {
  return <div className="loading-state" role="status"><span className="spinner" aria-hidden="true" />{label}</div>
}

function NotFoundPage() {
  return <section className="work-page not-found"><p className="eyebrow">NOT FOUND</p><h1>This page isn’t here.</h1><Link to="/" className="primary-button link-button">Start a repository review</Link></section>
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : 'An unexpected error occurred. Please try again.'
}

function formatDate(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? 'Time unavailable' : new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}

function displayDimension(value: string) {
  if (value.toLowerCase() === 'performance/scalability') return 'Performance & scalability'
  return value.replaceAll('_', ' ').replace(/\b\w/g, (letter) => letter.toUpperCase())
}

function formatStage(value: string) {
  return value.replaceAll('_', ' ').replace(/\b\w/g, (letter) => letter.toUpperCase())
}

export default App
