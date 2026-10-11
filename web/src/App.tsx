import { useEffect, useRef, useState } from 'react';
import { onAuthStateChanged, getRedirectResult, type User } from 'firebase/auth';
import { collection, limit, onSnapshot, orderBy, query } from 'firebase/firestore';
import { Bell, Check, ChevronRight, Clock3, History, Mic, Moon, Send, Settings2, Sun, Trash2, X } from 'lucide-react';
import type { ReminderRecord } from '../../shared/src/schedule';
import { auth, callable, database, enablePush, needsPushRegistration, signIn, signOutUser } from './firebase';
import { microphoneErrorMessage, startVoiceRecording, withTimeout, type VoiceRecording } from './voice';

type View = 'home' | 'upcoming' | 'history' | 'settings';
type Theme = 'system' | 'light' | 'dark';
type Proposal = { id: string; readback: string; preview: number[]; action: string };
type Turn = { id: string; user: string; assistant: string; createdAt: number };
type Event = { id: string; reminderId: string; action: string; at: number };

const call = {
  transcribe: callable<{ audioBase64: string; mimeType: string }, { transcript: string }>('transcribeAudio'),
  propose: callable<{ text: string; conversationId: string; timeZone: string; pendingProposalId?: string }, { conversationId: string; reply: string; proposal: Proposal | null }>('proposeAction'),
  confirm: callable<{ proposalId: string }, { reminderId: string; action: string }>('confirmProposal'),
  act: callable<{ reminderId: string; action: string; snoozeMinutes?: number; requestId: string; occurrenceId?: string }, unknown>('applyOccurrenceAction'),
  deleteConversation: callable<{ conversationId: string }, { deleted: boolean }>('deleteConversation'),
};

function dateLabel(ms: number | null | undefined) {
  if (ms == null) return 'No upcoming time';
  return new Intl.DateTimeFormat(undefined, { weekday: 'short', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' }).format(ms);
}

function getConversationId(uid: string) {
  const key = `remind-conversation-id:${uid}`;
  const prior = localStorage.getItem(key);
  if (prior) return prior;
  const id = crypto.randomUUID();
  localStorage.setItem(key, id);
  return id;
}

function speak(text: string) {
  if (!('speechSynthesis' in window)) return;
  window.speechSynthesis.cancel();
  const utterance = new SpeechSynthesisUtterance(text);
  utterance.rate = 1.03;
  window.speechSynthesis.speak(utterance);
}

async function blobToBase64(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result).split(',')[1] || '');
    reader.onerror = () => reject(new Error('Could not read recording.'));
    reader.readAsDataURL(blob);
  });
}

export default function App() {
  const [user, setUser] = useState<User | null>(null);
  const [authReady, setAuthReady] = useState(false);
  const [view, setView] = useState<View>('home');
  const [theme, setTheme] = useState<Theme>(() => (localStorage.getItem('remind-theme') as Theme) || 'system');
  const [conversationId, setConversationId] = useState('');
  const [turns, setTurns] = useState<Turn[]>([]);
  const [reminders, setReminders] = useState<ReminderRecord[]>([]);
  const [events, setEvents] = useState<Event[]>([]);
  const [proposal, setProposal] = useState<Proposal | null>(null);
  const [draft, setDraft] = useState('');
  const [search, setSearch] = useState('');
  const [busy, setBusy] = useState(false);
  const [recording, setRecording] = useState(false);
  const [startingRecording, setStartingRecording] = useState(false);
  const [workingLabel, setWorkingLabel] = useState('Working on it…');
  const [recordingMode, setRecordingMode] = useState<VoiceRecording['mode']>('wav');
  const [notice, setNotice] = useState('');
  const [voiceReply, setVoiceReply] = useState(true);
  const recorderRef = useRef<VoiceRecording | null>(null);
  const recordingStartRef = useRef(0);
  const recordingTimeoutRef = useRef<number | null>(null);
  const endRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => () => {
    recordingStartRef.current++;
    if (recordingTimeoutRef.current !== null) window.clearTimeout(recordingTimeoutRef.current);
    void recorderRef.current?.cancel();
  }, []);

  useEffect(() => {
    const unsubscribe = onAuthStateChanged(auth, next => {
      setReminders([]); setTurns([]); setEvents([]); setProposal(null);
      setConversationId(next ? getConversationId(next.uid) : '');
      setUser(next); setAuthReady(true);
    });
    getRedirectResult(auth).catch(error => setNotice(error.message));
    return unsubscribe;
  }, []);
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    localStorage.setItem('remind-theme', theme);
    const media = matchMedia('(prefers-color-scheme: dark)');
    const syncColor = () => {
      document.querySelector('meta[name="theme-color"]')?.setAttribute('content', theme === 'dark' || (theme === 'system' && media.matches) ? '#151722' : '#f7f8fc');
    };
    syncColor(); media.addEventListener('change', syncColor);
    return () => media.removeEventListener('change', syncColor);
  }, [theme]);
  useEffect(() => {
    if (!user || !conversationId || !user.emailVerified || !user.providerData.some(provider => provider.providerId === 'google.com')) return;
    const base = collection(database, 'users', user.uid, 'reminders');
    const stopReminders = onSnapshot(base, snapshot => {
      setReminders(snapshot.docs.map(doc => ({ ...doc.data(), id: doc.id } as ReminderRecord)));
    }, error => setNotice(error.message));
    const stopTurns = onSnapshot(query(collection(database, 'users', user.uid, 'conversations', conversationId, 'turns'), orderBy('createdAt', 'desc'), limit(100)), snapshot => {
      setTurns(snapshot.docs.map(doc => ({ ...doc.data(), id: doc.id } as Turn)).reverse());
    }, error => setNotice(error.message));
    const stopEvents = onSnapshot(query(collection(database, 'users', user.uid, 'events'), orderBy('at', 'desc'), limit(100)), snapshot => {
      setEvents(snapshot.docs.map(doc => ({ ...doc.data(), id: doc.id } as Event)));
    }, error => setNotice(error.message));
    return () => { stopReminders(); stopTurns(); stopEvents(); };
  }, [user, conversationId]);
  useEffect(() => { endRef.current?.scrollIntoView({ behavior: 'smooth' }); }, [turns.length, proposal]);
  useEffect(() => {
    if (!user || !user.emailVerified || !user.providerData.some(provider => provider.providerId === 'google.com')) return;
    const params = new URLSearchParams(location.search);
    const action = params.get('action');
    const reminderId = params.get('reminder');
    if (!action || !reminderId || !['done', 'snooze'].includes(action)) return;
    const requestId = params.get('requestId') || crypto.randomUUID();
    const occurrenceId = params.get('occurrenceId') || undefined;
    history.replaceState(null, '', '/');
    call.act({ action, reminderId, snoozeMinutes: action === 'snooze' ? 10 : undefined, requestId, occurrenceId })
      .then(() => setNotice(action === 'done' ? 'Marked done.' : 'Snoozed for 10 minutes.'))
      .catch(error => setNotice(error.message));
  }, [user]);

  async function sendText(text: string) {
    const value = text.trim();
    if (!value || busy) return;
    setBusy(true); setWorkingLabel('Understanding your request…'); setNotice(''); setDraft(''); setView('home');
    try {
      if (proposal && /^(yes|confirm|do it|okay|ok)$/i.test(value)) {
        await confirm();
        return;
      }
      const result = await call.propose({ text: value, conversationId, timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone, pendingProposalId: proposal?.id });
      setConversationId(result.conversationId);
      localStorage.setItem('remind-conversation-id', result.conversationId);
      setProposal(result.proposal);
      if (voiceReply) speak(result.reply);
    } catch (error) { setNotice(error instanceof Error ? error.message : 'Request failed.'); }
    finally { setBusy(false); }
  }

  async function confirm() {
    if (!proposal) return;
    // Begin on the confirmation tap so the browser can show its permission prompt.
    const pushNeeded = !!user && needsPushRegistration(user.uid);
    const pushSetup = pushNeeded
      ? enablePush(setNotice).then(() => '').catch(error => error instanceof Error ? error.message : 'Could not enable notifications.')
      : Promise.resolve('');
    setBusy(true);
    setWorkingLabel('Saving reminder…');
    try {
      await call.confirm({ proposalId: proposal.id });
      setNotice(pushNeeded ? 'Saved. Allow Firefox notifications to get alerts.'
        : 'Notification' in window && Notification.permission === 'denied'
          ? 'Saved, but Firefox notifications are blocked. Allow them in browser settings.' : 'Saved.');
      setProposal(null);
      if (pushNeeded) void pushSetup.then(pushError => setNotice(pushError
        ? `Saved, but notifications are off: ${pushError}` : 'Saved. Notifications enabled.'));
      if (voiceReply) speak('Saved.');
    } catch (error) {
      setNotice(error instanceof Error ? error.message : 'Could not save.');
    }
    finally { setBusy(false); }
  }

  async function finishRecording(capture: VoiceRecording) {
    if (recorderRef.current !== capture) return;
    recorderRef.current = null;
    if (recordingTimeoutRef.current !== null) window.clearTimeout(recordingTimeoutRef.current);
    recordingTimeoutRef.current = null;
    setRecording(false); setBusy(true); setWorkingLabel('Finishing recording…');
    try {
      const audio = await withTimeout(capture.stop(), 4000, 'The recording did not stop. Please try again.');
      if (audio.size > 5_000_000) throw new Error('Recording exceeds 5 MB. Please try a shorter message.');
      setWorkingLabel('Transcribing your speech…');
      const audioBase64 = await blobToBase64(audio);
      const { transcript } = await withTimeout(call.transcribe({ audioBase64, mimeType: audio.type }), 45_000,
        'Transcription timed out. Check your connection and try again.');
      if (!transcript.trim()) throw new Error('No speech was detected. Please try again and speak for a few seconds.');
      setBusy(false);
      await sendText(transcript);
    } catch (error) { setBusy(false); setNotice(error instanceof Error ? error.message : 'Transcription failed.'); }
  }

  async function toggleRecording() {
    if (startingRecording) {
      recordingStartRef.current++;
      setStartingRecording(false);
      return;
    }
    if (recording) {
      if (recorderRef.current) await finishRecording(recorderRef.current);
      return;
    }
    if (busy) return;
    const startId = ++recordingStartRef.current;
    setStartingRecording(true); setNotice('');
    try {
      window.speechSynthesis?.cancel();
      const capture = await startVoiceRecording();
      if (startId !== recordingStartRef.current) { await capture.cancel(); return; }
      recorderRef.current = capture;
      setRecordingMode(capture.mode);
      setRecording(true); setStartingRecording(false);
      recordingTimeoutRef.current = window.setTimeout(() => { void finishRecording(capture); }, 60_000);
    } catch (error) {
      if (startId === recordingStartRef.current) {
        setStartingRecording(false);
        setNotice(microphoneErrorMessage(error));
      }
    }
  }

  async function act(reminderId: string, action: 'done' | 'dismiss' | 'snooze') {
    setBusy(true);
    try {
      await call.act({ reminderId, action, snoozeMinutes: action === 'snooze' ? 10 : undefined, requestId: crypto.randomUUID() });
      setNotice(action === 'snooze' ? 'Snoozed 10 minutes.' : action === 'dismiss' ? 'Alert hidden. Future nudges remain active.' : 'Marked done.');
    } catch (error) { setNotice(error instanceof Error ? error.message : 'Action failed.'); }
    finally { setBusy(false); }
  }

  async function clearConversation() {
    setBusy(true);
    try {
      await call.deleteConversation({ conversationId });
      const next = crypto.randomUUID();
      localStorage.setItem(`remind-conversation-id:${user!.uid}`, next);
      setConversationId(next); setProposal(null); setNotice('Conversation deleted.');
    } catch (error) { setNotice(error instanceof Error ? error.message : 'Could not delete history.'); }
    finally { setBusy(false); }
  }

  const active = reminders.filter(r => r.status === 'active').sort((a, b) => (a.nextDueAt ?? Infinity) - (b.nextDueAt ?? Infinity));
  const visible = active.filter(r => r.title.toLowerCase().includes(search.toLowerCase()));
  const authorized = !!user && user.emailVerified && user.providerData.some(provider => provider.providerId === 'google.com');

  if (!authReady) return <main className="center-screen"><div className="spinner" /><p>Opening Remind…</p></main>;
  if (!authorized) return <main className="center-screen sign-in">
    <div className="logo"><Mic size={34} /></div>
    <h1>Remind</h1><p>Speak naturally. Stay on top of what matters.</p>
    {user ? <><p className="error">Sign in with a verified Google account.</p><button className="primary" onClick={() => signOutUser().catch(error => setNotice(error.message))}>Use another account</button></>
      : <button className="primary" onClick={() => signIn().catch(error => setNotice(error.message))}>Continue with Google</button>}
    {notice && <p className="error">{notice}</p>}
  </main>;

  return <div className="app-shell">
    <header className="topbar"><div className="brand"><div className="brand-mark"><Mic size={20} /></div><span>Remind</span></div>
      <button className="icon-button" aria-label="Settings" onClick={() => setView('settings')}><Settings2 size={21} /></button></header>
    <main className="main-content">
      {view === 'home' && <section className="home">
        <div className="hero"><div className="eyebrow">YOUR PERSONAL ASSISTANT</div><h1>What should I remind<br />you about?</h1><p>Say it your way. I’ll read it back before saving.</p></div>
        <div className="conversation" aria-live="polite">
          {turns.length === 0 && <div className="example-card"><span className="sparkle">✦</span><div><strong>Try something like</strong><p>“Remind me every weekday at 8:30 to take my vitamins.”</p></div></div>}
          {turns.map(turn => <div className="turn" key={turn.id}><div className="bubble user-bubble">{turn.user}</div><div className="bubble assistant-bubble">{turn.assistant}</div></div>)}
          {proposal && <div className="proposal-card"><div className="proposal-title"><Check size={18} /> Ready to save</div><p>{proposal.readback}</p>
            {!!proposal.preview.length && <div className="preview-times">{proposal.preview.map(time => <span key={time}>{dateLabel(time)}</span>)}</div>}
            <div className="proposal-actions"><button className="primary" disabled={busy} onClick={confirm}>Confirm</button><button className="secondary" onClick={() => setProposal(null)}>Cancel</button></div>
          </div>}
          {busy && <div className="thinking"><span className="spinner" /> {workingLabel}</div>}
          {startingRecording && <div className="thinking"><span className="spinner" /> Starting microphone…</div>}
          <div ref={endRef} />
        </div>
        <form className="composer" onSubmit={event => { event.preventDefault(); void sendText(draft); }}>
          <input aria-label="Type a reminder request" value={draft} onChange={event => setDraft(event.target.value)} placeholder="Ask for a reminder…" disabled={busy || recording || startingRecording} />
          <button type="submit" className="send-button" aria-label="Send" disabled={!draft.trim() || busy || startingRecording}><Send size={19} /></button>
          <button type="button" className={`mic-button ${recording ? 'is-recording' : ''} ${startingRecording ? 'is-starting' : ''}`} aria-label={recording ? 'Stop recording' : startingRecording ? 'Cancel microphone startup' : 'Start recording'} onClick={toggleRecording} disabled={busy}><Mic size={23} /></button>
        </form>
        <div className="composer-hint">{recording ? `Listening${recordingMode === 'browser' ? ' (browser recorder)' : ''}… tap the microphone to stop` : startingRecording ? 'If Firefox asks, allow microphone access · Tap again to cancel' : 'Tap the microphone to speak · Changes need your confirmation'}</div>
        {active.length > 0 && <button className="upcoming-peek" onClick={() => setView('upcoming')}><Clock3 size={17} /><span>Next: {active[0].title}</span><strong>{dateLabel(active[0].nextDueAt)}</strong><ChevronRight size={17} /></button>}
      </section>}
      {view === 'upcoming' && <section className="page"><div className="page-heading"><div className="eyebrow">STAY ON TRACK</div><h1>Upcoming</h1><p>{active.length} active reminder{active.length === 1 ? '' : 's'}</p></div>
        <input className="search" value={search} onChange={event => setSearch(event.target.value)} placeholder="Search reminders" aria-label="Search reminders" />
        <div className="reminder-list">{visible.length ? visible.map(r => <article className="reminder-card" key={r.id}><div className="reminder-icon"><Bell size={19} /></div><div className="reminder-body"><h2>{r.title}</h2><p>{r.currentScheduledAt && r.currentScheduledAt < Date.now() ? `Overdue since ${dateLabel(r.currentScheduledAt)} · Next attempt ${dateLabel(r.nextDueAt)}` : dateLabel(r.nextDueAt)}</p>{r.repeatUntilDoneMinutes && <span className="chip">Repeats every {r.repeatUntilDoneMinutes} min until Done</span>}
          <div className="card-actions"><button disabled={busy} onClick={() => act(r.id, 'done')}><Check size={16} /> Done</button><button disabled={busy} onClick={() => act(r.id, 'snooze')}><Clock3 size={16} /> Snooze</button><button disabled={busy} onClick={() => act(r.id, 'dismiss')}><X size={16} /> Dismiss</button><button onClick={() => { setDraft(`Change my reminder “${r.title}” to `); setView('home'); }}>Edit</button></div></div></article>) : <div className="empty-state">No reminders found. Tap the microphone to make one.</div>}</div>
      </section>}
      {view === 'history' && <section className="page"><div className="page-heading"><div className="eyebrow">RECENT ACTIVITY</div><h1>History</h1><p>Your completed and canceled reminders</p></div>
        <div className="history-list">{reminders.filter(r => r.status !== 'active').length ? reminders.filter(r => r.status !== 'active').map(r => <div className="history-item" key={r.id}><div className="history-dot" /><div><strong>{r.status}</strong><span>{r.title}</span></div><time>{dateLabel(r.updatedAt)}</time></div>) : <div className="empty-state">No completed or canceled reminders yet.</div>}</div>
        <h2 className="activity-heading">Recent actions</h2><div className="history-list">{events.length ? events.map(e => <div className="history-item" key={e.id}><div className="history-dot" /><div><strong>{e.action}</strong><span>{reminders.find(r => r.id === e.reminderId)?.title || 'Reminder'}</span></div><time>{dateLabel(e.at)}</time></div>) : <div className="empty-state">Nothing here yet.</div>}</div></section>}
      {view === 'settings' && <section className="page settings-page"><div className="page-heading"><div className="eyebrow">MAKE IT YOURS</div><h1>Settings</h1></div>
        <div className="setting-card"><div><strong>Appearance</strong><p>Follow your phone or choose a look</p></div><div className="segmented">{(['system', 'light', 'dark'] as Theme[]).map(mode => <button className={theme === mode ? 'selected' : ''} key={mode} onClick={() => setTheme(mode)}>{mode === 'light' ? <Sun size={15} /> : mode === 'dark' ? <Moon size={15} /> : null}{mode}</button>)}</div></div>
        <div className="setting-card"><div><strong>Notifications</strong><p>Allow Firefox to send reminders to your phone</p></div><button className="secondary" onClick={() => enablePush(setNotice).then(() => setNotice('Test notification sent.')).catch(error => setNotice(error.message))}>Enable & test</button></div>
        <div className="setting-card"><div><strong>Spoken replies</strong><p>Read responses aloud after voice or text requests</p></div><label className="switch"><input type="checkbox" checked={voiceReply} onChange={event => setVoiceReply(event.target.checked)} /><span /></label></div>
        <div className="setting-card"><div><strong>Conversation history</strong><p>Kept until you delete it. Raw audio is not saved.</p></div><button className="danger-text" disabled={busy} onClick={clearConversation}><Trash2 size={17} /> Delete conversation</button></div>
        <div className="setting-card"><div><strong>Account</strong><p>{user.email}</p></div><button className="secondary" onClick={() => signOutUser().catch(error => setNotice(error.message))}>Sign out</button></div>
      </section>}
    </main>
    {notice && <div className="toast" role="status"><span>{notice}</span><button aria-label="Dismiss notice" onClick={() => setNotice('')}><X size={16} /></button></div>}
    <nav className="bottom-nav" aria-label="Main navigation">
      <button className={view === 'home' ? 'active' : ''} onClick={() => setView('home')}><Mic size={20} /><span>Ask</span></button>
      <button className={view === 'upcoming' ? 'active' : ''} onClick={() => setView('upcoming')}><Clock3 size={20} /><span>Upcoming</span></button>
      <button className={view === 'history' ? 'active' : ''} onClick={() => setView('history')}><History size={20} /><span>History</span></button>
      <button className={view === 'settings' ? 'active' : ''} onClick={() => setView('settings')}><Settings2 size={20} /><span>Settings</span></button>
    </nav>
  </div>;
}
