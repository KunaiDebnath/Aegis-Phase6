import { useState, type ReactNode } from 'react'

type Screen =
  | 'splash' | 'onboarding' | 'role' | 'victim' | 'confirm' | 'active'
  | 'voice' | 'guardian' | 'alert' | 'call' | 'contacts' | 'history'
  | 'details' | 'diagnostics' | 'settings'

type IconName = 'shield' | 'home' | 'mic' | 'user' | 'clock' | 'settings' | 'pin' | 'phone' | 'check' | 'signal' | 'bell' | 'arrow' | 'close' | 'menu'

const paths: Record<IconName, ReactNode> = {
  shield: <path d="M12 3 5 6v5c0 4.5 2.8 8 7 10 4.2-2 7-5.5 7-10V6l-7-3Z" />,
  home: <><path d="m3 11 9-8 9 8" /><path d="M5 10v10h14V10M9 20v-6h6v6" /></>,
  mic: <><rect x="9" y="3" width="6" height="12" rx="3" /><path d="M5 11a7 7 0 0 0 14 0M12 18v3M9 21h6" /></>,
  user: <><circle cx="12" cy="8" r="4" /><path d="M4 21a8 8 0 0 1 16 0" /></>,
  clock: <><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></>,
  settings: <><circle cx="12" cy="12" r="3" /><path d="M19 12a7 7 0 0 0-.1-1l2-1.5-2-3.4-2.3 1A8 8 0 0 0 15 6l-.3-2.5h-4L10.4 6a8 8 0 0 0-1.7 1L6.4 6 4.5 9.5 6.4 11a7 7 0 0 0 0 2l-2 1.5 2 3.4 2.3-1a8 8 0 0 0 1.7 1l.3 2.5h4L15 18a8 8 0 0 0 1.7-1l2.3 1 2-3.4-2-1.5a7 7 0 0 0 .1-1Z" /></>,
  pin: <><path d="M20 10c0 5-8 11-8 11S4 15 4 10a8 8 0 1 1 16 0Z" /><circle cx="12" cy="10" r="2.5" /></>,
  phone: <path d="M7 3 4 5c0 8 7 15 15 15l2-3-5-3-2 2c-3-1-5-3-6-6l2-2-3-5Z" />,
  check: <path d="m5 12 4 4L19 6" />,
  signal: <><path d="M4 17h2v3H4zM9 13h2v7H9zM14 9h2v11h-2zM19 5h2v15h-2z" /></>,
  bell: <><path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9Z" /><path d="M10 21h4" /></>,
  arrow: <path d="m9 18 6-6-6-6" />,
  close: <path d="m6 6 12 12M18 6 6 18" />,
  menu: <path d="M4 7h16M4 12h16M4 17h16" />,
}

function Icon({ name, size = 20 }: { name: IconName; size?: number }) {
  return <svg aria-hidden="true" width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">{paths[name]}</svg>
}

const statusTone = { good: 'good', wait: 'wait', bad: 'bad', neutral: 'neutral' }
function Status({ label, tone = 'good' }: { label: string; tone?: keyof typeof statusTone }) {
  return <span className={`status ${tone}`}><span className="status-dot" />{label}</span>
}

function TopBar({ title, subtitle, onMenu, onBack }: { title: string; subtitle?: string; onMenu?: () => void; onBack?: () => void }) {
  return <header className="topbar">
    <button className="icon-btn" aria-label={onBack ? 'Go back' : 'Open all screens'} onClick={onBack || onMenu}>
      <Icon name={onBack ? 'arrow' : 'menu'} />
    </button>
    <div><strong>{title}</strong>{subtitle && <small>{subtitle}</small>}</div>
    <div className="avatar">AS</div>
  </header>
}

function PrimaryButton({ children, onClick, danger, quiet }: { children: ReactNode; onClick?: () => void; danger?: boolean; quiet?: boolean }) {
  return <button className={`button ${danger ? 'danger' : ''} ${quiet ? 'quiet' : ''}`} onClick={onClick}>{children}</button>
}

function InfoRow({ icon, label, value, status, action }: { icon: IconName; label: string; value?: string; status?: ReactNode; action?: () => void }) {
  return <button className="info-row" onClick={action} disabled={!action}>
    <span className="row-icon"><Icon name={icon} /></span>
    <span className="row-copy"><strong>{label}</strong>{value && <small>{value}</small>}</span>
    {status || (action && <Icon name="arrow" size={18} />)}
  </button>
}

const timeline = [
  ['14:32:08', 'SOS activated', 'Manual button'],
  ['14:32:09', 'Location acquired', 'Accuracy 12 m'],
  ['14:32:11', 'Alert submitted', 'Guardian notified'],
  ['14:32:15', 'SMS sent', 'Carrier accepted'],
  ['14:32:21', 'Audio connected', 'LiveKit session'],
]

function Timeline({ partial = false }: { partial?: boolean }) {
  return <div className="timeline">{timeline.slice(0, partial ? 3 : 5).map(([time, title, detail], i) =>
    <div className="timeline-item" key={title}><span className={`timeline-mark ${i === 0 ? 'critical' : ''}`}><Icon name="check" size={13} /></span><div><strong>{title}</strong><small>{time} · {detail}</small></div></div>
  )}</div>
}

function BottomNav({ current, go, guardian }: { current: Screen; go: (s: Screen) => void; guardian?: boolean }) {
  const items: [Screen, IconName, string][] = guardian
    ? [['guardian', 'home', 'Home'], ['alert', 'bell', 'Alerts'], ['history', 'clock', 'History'], ['settings', 'settings', 'Settings']]
    : [['victim', 'home', 'Home'], ['voice', 'mic', 'Voice'], ['contacts', 'user', 'Contacts'], ['history', 'clock', 'History'], ['settings', 'settings', 'Settings']]
  return <nav className="bottom-nav">{items.map(([id, icon, label]) =>
    <button key={id} className={current === id ? 'selected' : ''} onClick={() => go(id)}><Icon name={icon} /><span>{label}</span></button>
  )}</nav>
}

function ScreenMenu({ go, close }: { go: (s: Screen) => void; close: () => void }) {
  const groups: [string, Screen[]][] = [['Start', ['splash', 'onboarding', 'role']], ['Person needing help', ['victim', 'confirm', 'active', 'voice', 'contacts']], ['Guardian', ['guardian', 'alert', 'call']], ['Records & system', ['history', 'details', 'diagnostics', 'settings']]]
  const labels: Record<Screen, string> = { splash: 'Splash', onboarding: 'Onboarding', role: 'Role selection', victim: 'Victim home', confirm: 'SOS confirmation', active: 'Active emergency', voice: 'Voice safety', guardian: 'Guardian dashboard', alert: 'Incoming alert', call: 'Live audio call', contacts: 'Emergency contacts', history: 'Emergency history', details: 'Event details', diagnostics: 'Diagnostics', settings: 'Settings' }
  return <div className="menu-panel"><div className="menu-head"><div><b>AEGIS</b><small>Prototype index · 15 screens</small></div><button className="icon-btn" onClick={close}><Icon name="close" /></button></div>
    {groups.map(([group, ids]) => <section key={group}><h3>{group}</h3>{ids.map(id => <button key={id} onClick={() => go(id)}>{labels[id]}<Icon name="arrow" size={17} /></button>)}</section>)}
  </div>
}

export default function App() {
  const [screen, setScreen] = useState<Screen>('victim')
  const [menu, setMenu] = useState(false)
  const [voiceEnabled, setVoiceEnabled] = useState(true)
  const [callState, setCallState] = useState<'incoming' | 'connecting' | 'connected' | 'ended'>('incoming')
  const go = (s: Screen) => { setScreen(s); setMenu(false) }
  const guardian = ['guardian', 'alert', 'call'].includes(screen)
  const top = (title: string, subtitle?: string, back?: Screen) => <TopBar title={title} subtitle={subtitle} onMenu={() => setMenu(true)} onBack={back ? () => go(back) : undefined} />

  let content: ReactNode
  if (screen === 'splash') content = <main className="splash"><div className="brand-mark"><Icon name="shield" size={48} /><span>+</span></div><h1>AEGIS</h1><p>Your Voice. Your Safety. Your Lifeline.</p><span className="loader" /><button className="text-link" onClick={() => go('onboarding')}>Continue</button></main>
  else if (screen === 'onboarding') content = <main className="onboarding">{top('Welcome to AEGIS', 'Safety without unnecessary complexity', 'splash')}<div className="onboard-art"><Icon name="shield" size={62} /></div><h1>Help, when you need it</h1><p>Send an SOS, share your latest location, and connect with your trusted guardian.</p><div className="feature-list"><InfoRow icon="phone" label="One clear SOS action" value="Start an emergency alert quickly" /><InfoRow icon="pin" label="Location sharing" value="Share only during an active event" /><InfoRow icon="mic" label="Voice protection" value="Activate with “I need help” when enabled" /></div><PrimaryButton onClick={() => go('role')}>Get started</PrimaryButton><small className="disclaimer">AEGIS supports trusted-contact communication. It does not replace emergency services.</small></main>
  else if (screen === 'role') content = <main>{top('Choose device role', 'You can change this later', 'onboarding')}<div className="page"><h1>How will you use AEGIS?</h1><p className="lead">This selection configures this device. It is not an account sign-in.</p><button className="role-card selected" onClick={() => go('victim')}><span className="row-icon"><Icon name="shield" /></span><span><b>Person Needing Help</b><small>Send SOS alerts to your trusted guardian.</small></span><span className="radio" /></button><button className="role-card" onClick={() => go('guardian')}><span className="row-icon"><Icon name="user" /></span><span><b>Guardian</b><small>Receive alerts and calls from a connected person.</small></span><span className="radio" /></button><PrimaryButton onClick={() => go('victim')}>Continue</PrimaryButton></div></main>
  else if (screen === 'victim') content = <main>{top('Good afternoon, Alex', 'Person needing help')}<div className="page home-page"><div className="protection"><span className="shield-good"><Icon name="shield" /></span><div><Status label="Protection ready" /><p>Core safety services are available</p></div></div><button className="sos-button" onClick={() => go('confirm')}><span>SEND SOS</span><small>Press for emergency help</small></button><button className="voice-strip" onClick={() => go('voice')}><Icon name="mic" /><span><b>Voice protection is on</b><small>Say “I need help” to activate</small></span><Status label="Active" /></button><section className="section"><div className="section-title"><h2>Safety status</h2><button onClick={() => go('diagnostics')}>Diagnostics</button></div><div className="surface rows"><InfoRow icon="user" label="Emergency contact" value="Maya Singh · +1 415 555 0138" status={<Status label="Ready" />} /><InfoRow icon="pin" label="Location" value="Precise location available" status={<Status label="Available" />} /><InfoRow icon="signal" label="Services" value="Backend and LiveKit" status={<Status label="Connected" />} /></div></section><section className="section"><div className="section-title"><h2>Recent activity</h2><button onClick={() => go('history')}>View all</button></div><div className="empty-compact"><Icon name="clock" /><span><b>No recent emergencies</b><small>Your emergency activity will appear here.</small></span></div></section></div><BottomNav current={screen} go={go} /></main>
  else if (screen === 'confirm') content = <main className="dimmed"><div className="fake-home">{top('Good afternoon, Alex')}<div className="sos-ghost">SEND SOS</div></div><div className="sheet"><div className="sheet-handle" /><span className="alert-icon"><Icon name="bell" size={26} /></span><h1>Send emergency alert?</h1><p>Your guardian will receive your alert and latest location. A call will start when available.</p><div className="notice"><b>Use this for a genuine emergency.</b><span>You can cancel after activation if you are safe.</span></div><PrimaryButton danger onClick={() => go('active')}>Send SOS now</PrimaryButton><PrimaryButton quiet onClick={() => go('victim')}>Cancel</PrimaryButton></div></main>
  else if (screen === 'active') content = <main>{top('Emergency in progress', 'Event AEG-240614-018')}<div className="page"><div className="active-banner"><span className="pulse" /><div><strong>SOS ACTIVE</strong><small>Started today at 2:32 PM · 04:18 elapsed</small></div></div><div className="location-box"><div><span className="eyebrow">CURRENT LOCATION</span><strong>125 Market Street, San Francisco</strong><small>Updated just now · Accuracy 12 m</small></div><button>View map</button></div><div className="status-grid"><div><Icon name="pin" /><Status label="Sharing" /><small>Location</small></div><div><Icon name="bell" /><Status label="Delivered" /><small>Guardian</small></div><div><Icon name="phone" /><Status label="Connected" /><small>Audio call</small></div><div><Icon name="check" /><Status label="Submitted" /><small>SMS</small></div></div><section className="section"><h2>Emergency timeline</h2><Timeline /></section><PrimaryButton quiet onClick={() => go('victim')}>Resolve emergency</PrimaryButton></div></main>
  else if (screen === 'voice') content = <main>{top('Voice safety', 'Person needing help')}<div className="page"><div className={`voice-hero ${voiceEnabled ? '' : 'off'}`}><span><Icon name="mic" size={30} /></span><h1>Voice protection {voiceEnabled ? 'is on' : 'is off'}</h1><p>{voiceEnabled ? 'Speech recognition is ready.' : 'The microphone is not listening.'}</p><Status label={voiceEnabled ? 'Active' : 'Disabled'} tone={voiceEnabled ? 'good' : 'neutral'} /></div><section className="section"><h2>Activation phrase</h2><div className="phrase">“I need help”</div><p className="support">When protection is enabled and this exact phrase is recognized, AEGIS starts the SOS workflow. Recognition occurs on this device.</p></section><div className="surface rows"><InfoRow icon="mic" label="Microphone permission" value="Allowed while using the app" status={<Status label="Allowed" />} /><InfoRow icon="signal" label="Speech recognition" value="Service available" status={<Status label="Ready" />} /></div><PrimaryButton danger={voiceEnabled} onClick={() => setVoiceEnabled(!voiceEnabled)}>{voiceEnabled ? 'Disable protection' : 'Enable protection'}</PrimaryButton><button className="test-phrase" disabled={!voiceEnabled} onClick={() => go('active')}>Simulate recognized phrase</button></div><BottomNav current={screen} go={go} /></main>
  else if (screen === 'guardian') content = <main>{top('Guardian home', 'Guardian device')}<div className="page"><div className="guardian-status"><div><span className="eyebrow">YOUR AVAILABILITY</span><h1>Ready to receive alerts</h1><p>Notifications and calls are available.</p></div><Status label="Online" /></div><section className="section"><h2>Connected person</h2><div className="person-card"><div className="person-avatar">AS</div><div><b>Alex Smith</b><small>Primary emergency contact</small></div><Status label="Connected" /></div></section><section className="section"><h2>Latest emergency</h2><div className="calm-empty"><Icon name="shield" size={30} /><b>No active emergency</b><p>When Alex sends an SOS, the alert and available location will appear here.</p><button onClick={() => go('alert')}>Preview incoming alert</button></div></section><section className="section"><div className="section-title"><h2>Emergency history</h2><button onClick={() => go('history')}>View all</button></div><div className="history-row"><span className="event-mark resolved"><Icon name="check" /></span><div><b>Resolved emergency</b><small>Jun 10 · 6:48 PM · 7 min</small></div><Icon name="arrow" /></div></section></div><BottomNav current={screen} go={go} guardian /></main>
  else if (screen === 'alert') content = <main className="alert-screen"><div className="alert-top"><span className="alert-badge"><Icon name="bell" /></span><span>EMERGENCY ALERT</span><small>Received now · 2:32 PM</small></div><div className="alert-person"><div className="large-avatar">AS</div><h1>Alex Smith needs help</h1><p>Primary connected person</p></div><div className="alert-location"><span className="row-icon"><Icon name="pin" /></span><div><b>Latest available location</b><strong>125 Market Street, San Francisco</strong><small>Updated just now · Accuracy 12 m</small></div></div><div className="alert-actions"><PrimaryButton onClick={() => go('call')}><Icon name="phone" /> Answer call</PrimaryButton><PrimaryButton quiet onClick={() => go('details')}><Icon name="pin" /> View location on map</PrimaryButton></div><button className="text-link light" onClick={() => go('guardian')}>Dismiss to dashboard</button></main>
  else if (screen === 'call') {
    const connected = callState === 'connected'
    content = <main className="call-screen">{top('Emergency audio', callState === 'ended' ? 'Call ended' : 'Encrypted LiveKit session', 'alert')}<div className="call-content"><div className="large-avatar">AS</div><h1>Alex Smith</h1><Status label={callState === 'incoming' ? 'Incoming call' : callState === 'connecting' ? 'Connecting…' : connected ? 'Connected · 00:42' : 'Call ended'} tone={connected ? 'good' : callState === 'ended' ? 'neutral' : 'wait'} /><p>{connected ? 'Connection quality: Good' : callState === 'incoming' ? 'Emergency audio call' : callState === 'connecting' ? 'Establishing secure audio…' : 'Duration 00:42'}</p></div><div className="call-controls"><button disabled={!connected}><Icon name="mic" /><span>Mute</span></button><button disabled={!connected}><Icon name="signal" /><span>Speaker</span></button></div><div className="call-primary">{callState === 'incoming' ? <><button className="answer" onClick={() => { setCallState('connecting'); setTimeout(() => setCallState('connected'), 700) }}><Icon name="phone" size={28} /></button><span>Answer</span></> : callState !== 'ended' ? <><button className="end" onClick={() => setCallState('ended')}><Icon name="phone" size={28} /></button><span>End call</span></> : <PrimaryButton onClick={() => { setCallState('incoming'); go('guardian') }}>Return to dashboard</PrimaryButton>}</div></main>
  } else if (screen === 'contacts') content = <main>{top('Emergency contact', 'Used for SOS alerts', 'victim')}<div className="page"><div className="selected-contact"><span className="person-avatar">MS</span><div><Status label="Selected contact" /><h2>Maya Singh</h2><p>Receives SOS alerts, location, and calls.</p></div></div><label className="field"><span>Full name</span><input defaultValue="Maya Singh" /></label><label className="field"><span>Phone number</span><input defaultValue="+1 415 555 0138" inputMode="tel" /><small>Include country code.</small></label><div className="surface rows"><InfoRow icon="signal" label="Backend sync" value="Contact matches stored record" status={<Status label="Synced" />} /></div><div className="button-row"><PrimaryButton quiet onClick={() => go('victim')}>Cancel</PrimaryButton><PrimaryButton onClick={() => go('victim')}>Save contact</PrimaryButton></div><button className="text-link danger-text">Remove contact</button></div><BottomNav current={screen} go={go} /></main>
  else if (screen === 'history') content = <main>{top('Emergency history', 'Events recorded by AEGIS')}<div className="page"><div className="filter-row"><button className="chip active">All events</button><button className="chip">Resolved</button><button className="chip">Needs review</button></div><div className="history-list">{[['Jun 10, 6:48 PM', 'Resolved', 'Manual SOS · 7 min', true], ['May 28, 9:14 PM', 'Resolved', 'Voice trigger · 3 min', true], ['May 03, 11:06 AM', 'Connection failed', 'Manual SOS · Location unavailable', false]].map(([date, state, meta, ok]) => <button className="history-row" key={String(date)} onClick={() => go('details')}><span className={`event-mark ${ok ? 'resolved' : 'failed'}`}><Icon name={ok ? 'check' : 'signal'} /></span><div><b>{state}</b><small>{date}</small><small>{meta}</small></div><Icon name="arrow" /></button>)}</div><div className="list-note">Showing example events for prototype review.</div></div><BottomNav current={screen} go={go} guardian={guardian} /></main>
  else if (screen === 'details') content = <main>{top('Emergency details', 'AEG-240610-011', 'history')}<div className="page"><div className="event-summary"><span className="event-mark resolved"><Icon name="check" /></span><div><Status label="Resolved" /><h1>Manual SOS</h1><p>Jun 10, 2024 · 6:48–6:55 PM</p></div></div><div className="surface rows"><InfoRow icon="pin" label="Location" value="125 Market Street · Accuracy 18 m" status={<Status label="Available" />} /><InfoRow icon="check" label="SMS" value="Carrier accepted at 6:48 PM" status={<Status label="Submitted" />} /><InfoRow icon="bell" label="Guardian notification" value="Opened at 6:49 PM" status={<Status label="Delivered" />} /><InfoRow icon="phone" label="Audio call" value="Connected · 5 min 14 sec" status={<Status label="Completed" />} /></div><section className="section"><h2>Event timeline</h2><Timeline /></section></div></main>
  else if (screen === 'diagnostics') content = <main>{top('Connection & diagnostics', 'No credentials are shown', 'settings')}<div className="page"><div className="diagnostic-summary"><Icon name="check" /><div><b>Core services ready</b><small>Last checked just now</small></div><button>Retry all</button></div><div className="surface rows">{[['signal','Network','Wi-Fi connected','Connected','good'],['signal','Backend API','Response 142 ms','Connected','good'],['pin','GPS & permission','Precise location allowed','Available','good'],['phone','SMS operation','Permission granted','Not tested','neutral'],['mic','Microphone & speech','Recognition available','Ready','good'],['bell','Firebase notifications','Device registered','Connected','good'],['phone','LiveKit audio','Reachable','Connected','good'],['signal','Device discovery','Not implemented','Unavailable','neutral']].map(([icon,label,value,status,tone]) => <InfoRow key={label} icon={icon as IconName} label={label} value={value} status={<Status label={status} tone={tone as keyof typeof statusTone} />} />)}</div><p className="support">Tests confirm service availability only. They do not send an emergency alert.</p></div></main>
  else content = <main>{top('Settings', guardian ? 'Guardian device' : 'Person needing help')}<div className="page settings-page"><section><h2>Device</h2><div className="surface rows"><InfoRow icon="user" label="Device role" value={guardian ? 'Guardian' : 'Person needing help'} action={() => go('role')} /><InfoRow icon="user" label="Emergency contact" value="Maya Singh" action={() => go('contacts')} /></div></section><section><h2>Protection</h2><div className="surface rows"><InfoRow icon="mic" label="Voice protection" value={voiceEnabled ? 'Enabled · “I need help”' : 'Disabled'} action={() => go('voice')} /><InfoRow icon="bell" label="Notifications" value="Emergency alerts enabled" action={() => {}} /></div></section><section><h2>Privacy & system</h2><div className="surface rows"><InfoRow icon="shield" label="Permissions & privacy" value="Review device permissions" action={() => go('diagnostics')} /><InfoRow icon="signal" label="Connection diagnostics" value="All core services ready" action={() => go('diagnostics')} /></div></section><div className="app-version"><div className="mini-brand"><Icon name="shield" /></div><b>AEGIS</b><small>Version 1.0.0 · Prototype</small></div></div><BottomNav current={screen} go={go} guardian={guardian} /></main>

  return <div className="app-shell"><div className="phone">{content}{menu && <><button className="menu-scrim" aria-label="Close menu" onClick={() => setMenu(false)} /><ScreenMenu go={go} close={() => setMenu(false)} /></>}</div><aside className="prototype-note"><b>AEGIS interactive prototype</b><span>390 × 844 Android viewport</span><span>Use the top-left menu to review all 15 screens.</span><button onClick={() => setMenu(true)}>Open screen index</button></aside></div>
}
