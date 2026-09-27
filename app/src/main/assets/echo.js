/* ════════════════════════════════════════════════════════
   ECHO 2.0 — Paul's phone assistant
   ════════════════════════════════════════════════════════ */
'use strict';

/* Browser preview: fake the phone so the page runs on a PC */
if (!window.Android) {
  const store = {}; let rem = [];
  window.Android = {
    listen(){ setTimeout(()=>onSpeech({type:'ready'}),200); setTimeout(()=>onSpeech({type:'error',code:'nomatch'}),2500); },
    stopListen(){}, hasMicPermission(){return true},
    speak(t,id){ setTimeout(()=>onSpeak({type:'start',id}),50); setTimeout(()=>onSpeak({type:'done',id}),600+t.length*20); },
    stopSpeaking(){}, setRate(){}, setPitch(){}, setLang(){return true},
    getPref(k){ return store[k]||'' }, setPref(k,v){ store[k]=v },
    ask(s,h,search,cb){ setTimeout(()=>onReply(cb,{text:"This is the browser preview, so I have no brain here. On your phone I'd answer that properly.",model:'preview'}),900) },
    askImage(s,h,cb){ setTimeout(()=>onReply(cb,{text:"That looks like a diagram of a plant cell. The big green blobs are chloroplasts.",model:'preview'}),900) },
    openApp(){return ''}, openUrl(){return true}, play(){return true},
    flashlight(){return true}, alarm(){return true}, timer(){return true},
    remind(at,t){ rem.push({id:rem.length+1,at,text:t}); return rem.length }, reminders(){return JSON.stringify(rem)},
    removeReminder(id){ rem = rem.filter(r=>r.id!==id) }, clearReminders(){ rem=[] },
    dial(){return true}, call(){}, contact(n,cb){ setTimeout(()=>onContact(cb,{status:'found',name:'Amma',number:'9876543210'}),200) },
    contactSplit(t,cb){ const w=t.split(' '); setTimeout(()=>onContact(cb,{status:'found',name:w[0][0].toUpperCase()+w[0].slice(1),number:'9876543210',rest:w.slice(1).join(' ')}),200) },
    whatsapp(){return true}, sms(){return true},
    photo(src,cb){ setTimeout(()=>onPhoto(cb,{ok:true,thumb:'data:image/svg+xml;utf8,'+encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="160" height="120"><rect width="160" height="120" fill="#1d3b2a"/><circle cx="80" cy="60" r="34" fill="#3e8f55"/><circle cx="70" cy="55" r="8" fill="#9be07a"/></svg>')}),400) },
    volume(){return 60}, ringer(){return 'ok'}, brightness(){return 'ok'}, battery(){return JSON.stringify({pct:72,charging:false})},
    openSettings(){}, perms(){return JSON.stringify({mic:true,contacts:false,phone:false,notify:true,overlay:false,writeSettings:false,exactAlarm:true,bg:false,tamilVoice:false})},
    requestPerm(){}, notifications(){return JSON.stringify({enabled:true,items:[{app:'WhatsApp',title:'Tharun',text:'bro where are you',t:Date.now()}]})}, markNotificationsRead(){},
    setBackground(){return true}, backgroundRunning(){return false},
    bof(cb){ setTimeout(()=>onBof(cb,{signals:[{name:'NIFTY',bull:false,level:'PDH',score:5,entry:25340.5,stop:25380,target:25260,t:Date.now()-3600e3,open:true}],prices:[{name:'NIFTY',price:25310.2,prev:25250,open:true}],errors:0}),900) },
    laptop(p,b,cb){ setTimeout(()=>onLaptop(cb,{code:200,body:'{"ok":true}'}),400) }
  };
}
const A = window.Android;
const $ = id => document.getElementById(id);

/* ── persona: Echo or Jane (persona.js) ── */
const P = window.PERSONA || {name:'Echo', user:'Paul', wake:['hey echo','echo'], strip:['echo'], intro:()=>'', lines:{}};
P.lines = P.lines || {};
/* Fixed app text is written for Echo and Paul; this swaps in the persona's names. */
function T(s){
  if (s == null) return s;
  return String(s).replace(/\bECHO\b/g, P.name.toUpperCase()).replace(/\bEcho\b/g, P.name).replace(/\bPaul\b/g, P.user);
}
function L(key, fallback){ return P.lines[key] != null ? P.lines[key] : T(fallback); }
if (P.colors) for (const k in P.colors) document.documentElement.style.setProperty(k, P.colors[k]);
document.title = P.name;

/* ════════════════════════════════════════════════════════
   CONFIG
   ════════════════════════════════════════════════════════ */
const WAKE_PHRASES = P.wake;
const SLEEP_WORDS  = ["go to sleep","goodbye","good bye","sleep now","that's all","thats all","that is all","dismiss","go back to sleep","bye echo"];
const STOP_WORDS   = ["stop","stop talking","quiet","enough","shut up","be quiet","cancel"];
const CONTEXT_TURNS = 20;

const WEB_APPS = {
  "youtube":"https://youtube.com","instagram":"https://instagram.com","snapchat":"https://snapchat.com",
  "whatsapp":"https://web.whatsapp.com","spotify":"https://open.spotify.com","netflix":"https://netflix.com",
  "twitter":"https://x.com","x":"https://x.com","facebook":"https://facebook.com","reddit":"https://reddit.com",
  "github":"https://github.com","gmail":"https://mail.google.com","google":"https://google.com",
  "amazon":"https://amazon.in","flipkart":"https://flipkart.com","chatgpt":"https://chatgpt.com",
  "chat gpt":"https://chatgpt.com","claude":"https://claude.ai","pinterest":"https://pinterest.com",
  "linkedin":"https://linkedin.com","fiverr":"https://fiverr.com","kaggle":"https://kaggle.com",
  "tradingview":"https://tradingview.com","maps":"https://maps.google.com","google maps":"https://maps.google.com"
};
const APP_ALIASES = {
  "camera":"camera","gallery":"gallery","photos":"photos","phone":"phone","dialer":"phone","contacts":"contacts",
  "messages":"messages","sms":"messages","clock":"clock","calculator":"calculator","calendar":"calendar",
  "play store":"play store","playstore":"play store","maps":"maps","google maps":"maps","files":"files",
  "file manager":"file manager","chrome":"chrome","browser":"chrome","gpay":"gpay","google pay":"google pay",
  "phonepe":"phonepe","phone pe":"phonepe","paytm":"paytm","whatsapp":"whatsapp","whats app":"whatsapp",
  "insta":"instagram","yt":"youtube","you tube":"youtube","yt music":"youtube music","gmail":"gmail",
  "telegram":"telegram","discord":"discord","snap":"snapchat","twitter":"x","bof":"reno's bof","reno's bof":"reno's bof"
};
const SETTINGS_WORDS = {"wifi":"wifi","wi-fi":"wifi","wi fi":"wifi","bluetooth":"bluetooth","display":"display",
  "brightness":"brightness","sound":"sound","volume":"volume","battery":"battery","settings":"main","location":"location","hotspot":"hotspot"};
const MARKET_NAMES = {NIFTY:'Nifty',BANKNIFTY:'Bank Nifty',SENSEX:'Sensex',CRUDE:'Crude oil',GOLD:'Gold',BTC:'Bitcoin',
  EURUSD:'Euro dollar',GBPUSD:'Pound dollar',USDJPY:'Dollar yen',USDINR:'Dollar rupee'};

/* ════════════════════════════════════════════════════════
   STATE
   ════════════════════════════════════════════════════════ */
const S = { SLEEPING:'standby', WAKE:'wake', LISTENING:'listening', THINKING:'thinking', SPEAKING:'speaking' };
let state = S.SLEEPING;
let history = [];
let memory = [];
let voiceTurn = false, followUp = false;
let level = 0, paused = false, hfTimer = null;
let quiz = null;           // {topic}
let awaiting = null;       // {kind:'message', ...} when Echo asked a question
let pending = {};

function pref(k,d){ const v = A.getPref(k); return v===''||v==null ? d : v; }
function setPref(k,v){ A.setPref(k,String(v)); }
const opt = {
  voice: pref('voice','1')==='1',
  follow: pref('follow','1')==='1',
  hf: pref('hf','0')==='1',
  rate: parseFloat(pref('rate','1.05')),
  pitch: parseFloat(pref('pitch', String(P.pitch || 1.0))),
  lang: pref('lang','en')
};
try { memory = JSON.parse(pref('memory','[]')); } catch(e){ memory = []; }
function saveMemory(){ setPref('memory', JSON.stringify(memory)); }

function systemPrompt(){
  const now = new Date();
  let p = P.intro(now) +
    "Right now it is " + now.toLocaleString('en-IN',{weekday:'long',year:'numeric',month:'long',day:'numeric',hour:'numeric',minute:'2-digit'}) + " India time. " +
    "You can control his phone. If Paul asks you to DO something on the phone, in any language or wording, reply with ONLY one line like <<do: open youtube>>, using one of these English commands: " +
    "open <app>, play <song>, play <song> on spotify, search for <query>, call <contact name or number>, message <contact name> <text>, " +
    "remind me at <time> to <task>, remind me in <n> minutes to <task>, set an alarm for <time>, set a timer for <n> minutes, torch on, torch off, " +
    "volume up, volume down, volume <percent>, brightness <percent>, battery, bof signals, laptop <command>. " +
    "Otherwise just answer normally.";
  if (memory.length) p += " Things Paul asked you to remember: " + memory.map(m => m.text).join('; ') + ".";
  if (opt.lang === 'ta') p += " Paul has switched you to Tamil: reply in natural spoken Tamil using Tamil script. Keep common English technical words as they are. The <<do: ...>> command itself stays in English.";
  if (quiz) p += ` You are running a spoken quiz on "${quiz.topic}" for Paul, a first-year biotech student. Ask ONE question at a time (short answer or multiple choice with options A to D read out). ` +
    "After he answers, say if he is right, give a one-line explanation, keep a running score out of the questions asked, then ask the next question. Make them progressively harder. Never ask more than one question per reply.";
  // the persona intro is used as written; the shared instructions get the persona's names
  const intro = P.intro(now);
  return intro + T(p.slice(intro.length));
}

/* ════════════════════════════════════════════════════════
   UI BASICS
   ════════════════════════════════════════════════════════ */
const logEl = $('log');
function setState(s){
  state = s;
  document.body.className = 's-' + (s===S.WAKE ? 'standby' : s);
  const label = {standby:'standby',wake:'standby',listening:'listening',thinking:'thinking',speaking:'speaking'}[s];
  $('stateTxt').textContent = (s===S.WAKE ? 'hey echo' : label);
  const col = {standby:'var(--dim)',wake:'var(--teal2)',listening:'var(--amber)',thinking:'var(--violet)',speaking:'var(--teal)'}[s];
  $('dot').style.background = col;
  $('dot').style.boxShadow = (s===S.SLEEPING) ? 'none' : '0 0 10px '+col;
  $('mic').classList.toggle('on', s===S.LISTENING);
  if (s===S.SLEEPING) hint(opt.hf ? 'Say "Hey Echo", or tap the orb' : 'Tap the orb and speak');
  if (s===S.WAKE) hint('Listening for "Hey Echo"…');
  if (s===S.LISTENING) hint(awaiting ? awaiting.prompt : "I'm listening…");
  if (s===S.THINKING) hint('Thinking…');
  if (s===S.SPEAKING) hint('Tap the orb to interrupt');
  typing(s===S.THINKING);
}
function hint(t, live){ const h=$('hint'); h.textContent=live ? t : T(t); h.classList.toggle('live', !!live); }
function toast(t){ const el=$('toast'); el.textContent=T(t); el.classList.add('show'); clearTimeout(toast._t); toast._t=setTimeout(()=>el.classList.remove('show'),2600); }

let typingEl = null;
function typing(on){
  if (on && !typingEl) { typingEl = document.createElement('div'); typingEl.className='typing'; typingEl.innerHTML='<i></i><i></i><i></i>'; logEl.appendChild(typingEl); scrollDown(); }
  if (!on && typingEl) { typingEl.remove(); typingEl = null; }
}
function scrollDown(){ logEl.scrollTop = logEl.scrollHeight; }

let saved = [];
function log(text, kind, persist=true){
  if (typingEl) typingEl.remove();
  const d = document.createElement('div');
  d.className = 'msg ' + kind;
  if (kind==='sys' || kind==='err') d.innerHTML = T(text);
  else if (kind==='card') d.innerHTML = text;
  else if (kind==='img') d.innerHTML = '<img alt="photo" src="'+text+'">';
  else d.textContent = text;
  logEl.appendChild(d);
  if (typingEl) logEl.appendChild(typingEl);
  scrollDown();
  if (persist && (kind==='user'||kind==='echo')) {
    saved.push({k:kind,t:text}); if (saved.length>60) saved = saved.slice(-60);
    saveChat();
  }
  return d;
}
function saveChat(){ setPref('chat', JSON.stringify({saved, history})); }
function loadChat(){
  try {
    const c = JSON.parse(pref('chat','{}'));
    saved = c.saved||[]; history = c.history||[];
    saved.forEach(m => log(m.t, m.k, false));
  } catch(e){ saved=[]; history=[]; }
}
function esc(s){ return String(s).replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c])); }
function link(label, js){ return `<a href="#" onclick="${js};return false">${label}</a>`; }

/* ════════════════════════════════════════════════════════
   VOICE OUT
   ════════════════════════════════════════════════════════ */
let afterSpeak = null, speakId = '';
function speak(text, then, raw){
  if (!raw) text = T(text);
  log(text, 'echo');
  afterSpeak = then || null;
  if (!opt.voice || paused) { const f = afterSpeak; afterSpeak=null; setState(S.SLEEPING); if (f) f(false); else resumeWake(); return; }
  setState(S.SPEAKING);
  speakId = 's' + Date.now() + Math.random().toString(36).slice(2,6);
  A.speak(text, speakId);
}
function hush(){ afterSpeak = null; speakId = ''; A.stopSpeaking(); }
window.onSpeak = function(e){
  if (e.id !== speakId) return;
  if (e.type==='start') { if (state!==S.SPEAKING) setState(S.SPEAKING); return; }
  if (e.type==='done') {
    speakId = '';
    if (state!==S.SPEAKING) return;
    setState(S.SLEEPING);
    const f = afterSpeak; afterSpeak = null;
    if (f) f(true); else resumeWake();
  }
};

/* ════════════════════════════════════════════════════════
   VOICE IN
   ════════════════════════════════════════════════════════ */
function listenForCommand(){
  clearTimeout(hfTimer);
  A.stopListen();
  setState(S.LISTENING);
  A.listen(true);
}
function startWakeLoop(delay=250){
  clearTimeout(hfTimer);
  if (!opt.hf || paused) return;
  hfTimer = setTimeout(()=>{
    if (!opt.hf || paused || (state!==S.SLEEPING && state!==S.WAKE)) return;
    setState(S.WAKE);
    A.listen(false);
  }, delay);
}
function resumeWake(){ if (opt.hf) startWakeLoop(400); }

window.onSpeech = function(e){
  if (e.type==='rms') { level = Math.max(0, Math.min(1, (e.level+2)/12)); return; }
  if (e.type==='partial') { if (state===S.LISTENING) hint(e.text, true); return; }
  if (e.type==='final') {
    level = 0;
    const text = (e.text||'').trim();
    if (state===S.WAKE) {
      const low = text.toLowerCase();
      const w = WAKE_PHRASES.find(p => low.includes(p));
      if (!w) { startWakeLoop(150); return; }
      const rest = low.slice(low.indexOf(w)+w.length).replace(/^[\s,.!?]+/,'').trim();
      if (rest.length > 2) { voiceTurn = true; submit(rest); }
      else wakeUp();
      return;
    }
    if (state===S.LISTENING) {
      if (!text) { didntCatch(); return; }
      voiceTurn = true;
      submit(text);
    }
    return;
  }
  if (e.type==='error') {
    level = 0;
    if (e.code==='permission') {
      setState(S.SLEEPING);
      if (opt.hf) { opt.hf=false; setPref('hf',0); syncUi(); }
      log('Echo needs the microphone to hear you. Allow it when Android asks, or ' + link('open app settings',"A.openSettings('app')") + '.', 'err', false);
      return;
    }
    if (e.code==='unavailable') {
      setState(S.SLEEPING);
      log('This phone has no speech recognition. Install or update the <b>Google</b> app. Typing still works.', 'err', false);
      return;
    }
    if (state===S.WAKE) {
      setState(S.SLEEPING);
      startWakeLoop(e.code==='network' ? 4000 : (e.code==='busy'||e.code==='client') ? 900 : 200);
      return;
    }
    if (state===S.LISTENING) {
      if (e.code==='network') { setState(S.SLEEPING); log("Voice needs internet. Check your connection, or type instead.", 'err', false); resumeWake(); return; }
      if (e.code==='nomatch' && !followUp) { didntCatch(); return; }
      followUp = false;
      if (awaiting) { awaiting = null; }
      setState(S.SLEEPING);
      resumeWake();
    }
  }
};
function didntCatch(){ speak(opt.lang==='ta' ? L('taDidnt',"புரியலை, மறுபடி சொல்லுங்க.") : L('didnt',"Didn't catch that."), ()=>{ setState(S.SLEEPING); resumeWake(); }); }
function wakeUp(){ voiceTurn = true; speak(opt.lang==='ta' ? L('taWake',"சொல்லுங்க Paul?") : L('wake',"Yes Paul?"), ()=>{ followUp=false; listenForCommand(); }); }

window.onMicPermission = function(e){ if (e.granted) toast('Mic ready'); };
window.onAppPause = function(){ paused = true; clearTimeout(hfTimer); if (state===S.LISTENING||state===S.WAKE) setState(S.SLEEPING); };
window.onAppResume = function(){ paused = false; renderPerms(); renderPower(); if (state===S.SLEEPING) resumeWake(); };
window.onPerms = function(){ renderPerms(); };

/* Widget / assistant button / "Hey Echo" from the background */
window.externalAction = function(kind, cmd){
  closeSheets();
  if (kind==='listen') { hush(); followUp=false; listenForCommand(); }
  else if (kind==='command' && cmd) { hush(); voiceTurn = true; submit(cmd); }
  else if (kind==='photo') { startPhoto('camera', ''); }
};

/* ════════════════════════════════════════════════════════
   CONVERSATION FLOW
   ════════════════════════════════════════════════════════ */
function submit(text){
  clearTimeout(hfTimer);
  A.stopListen();
  followUp = false;
  log(text, 'user');
  if (awaiting) { const a = awaiting; awaiting = null; return a.then(text); }
  handle(text);
}
function reply(text){
  speak(text, (spoke)=>{
    const keepGoing = voiceTurn && (opt.follow || quiz) && !paused;
    if (spoke && keepGoing) { followUp = true; listenForCommand(); }
    else { voiceTurn = false; resumeWake(); }
  }, true);
}
function done(text){ speak(text, ()=>{ voiceTurn=false; resumeWake(); }); }
/* Echo asks something and waits for the answer (voice or typed) */
function ask(question, prompt, then){
  awaiting = {prompt, then};
  speak(question, (spoke)=>{
    if (!awaiting) return;
    if (spoke && voiceTurn && !paused) listenForCommand();
    else { setState(S.SLEEPING); hint(prompt, true); $('entry').focus(); }
  });
}

/* ════════════════════════════════════════════════════════
   TIME PARSING  ("at 6:30 pm", "in 10 minutes", "tomorrow at 7")
   ════════════════════════════════════════════════════════ */
const NUM = {a:1,an:1,one:1,two:2,three:3,four:4,five:5,six:6,seven:7,eight:8,nine:9,ten:10,fifteen:15,twenty:20,thirty:30,forty:40,'forty five':45,sixty:60,half:0.5};
function num(w){ w = (w||'').trim(); return /^\d+(\.\d+)?$/.test(w) ? parseFloat(w) : (NUM[w] ?? NaN); }
function parseWhen(s){
  let m, at = null, used = '';
  const now = new Date();
  if ((m = s.match(/\bin (half an hour|an hour and a half|(\d+|an?|one|two|three|four|five|six|seven|eight|nine|ten|fifteen|twenty|thirty|forty|sixty)\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?))\b/))) {
    used = m[0];
    if (m[1]==='half an hour') at = new Date(+now + 30*60e3);
    else if (m[1]==='an hour and a half') at = new Date(+now + 90*60e3);
    else {
      const n = num(m[2]), u = m[3];
      const mult = /^h/.test(u) ? 3600e3 : /^m/.test(u) ? 60e3 : 1e3;
      at = new Date(+now + n*mult);
    }
    return {at, used};
  }
  const dayWord = (s.match(/\b(tomorrow|today|tonight|day after tomorrow)\b/)||[])[1];
  if ((m = s.match(/\bat (\d{1,2})(?:[:. ](\d{2}))?\s*(a\.?m\.?|p\.?m\.?|in the morning|in the afternoon|in the evening|at night)?/)) ||
      (m = s.match(/\b(\d{1,2})[:.](\d{2})\s*(a\.?m\.?|p\.?m\.?)?/)) ||
      (m = s.match(/\b(\d{1,2})()\s*(a\.?m\.?|p\.?m\.?)/))) {
    used = m[0];
    let h = parseInt(m[1]), min = m[2] ? parseInt(m[2]) : 0;
    const ap = (m[3]||'').replace(/\./g,'');
    if (h > 23 || min > 59) return null;
    let explicit = false;
    if (/^pm|afternoon|evening|night/.test(ap)) { if (h < 12) h += 12; explicit = true; }
    else if (/^am|morning/.test(ap)) { if (h === 12) h = 0; explicit = true; }
    else if (dayWord==='tonight' && h < 12) { h += 12; explicit = true; }
    at = new Date(now); at.setSeconds(0,0); at.setHours(h, min);
    if (dayWord==='tomorrow') at.setDate(at.getDate()+1);
    else if (dayWord==='day after tomorrow') at.setDate(at.getDate()+2);
    else if (at <= now) {
      if (!explicit && h < 12 && new Date(+at + 12*3600e3) > now) at = new Date(+at + 12*3600e3);
      else at.setDate(at.getDate()+1);
    }
    return {at, used: used + (dayWord ? ' ' + dayWord : ''), dayWord};
  }
  if (dayWord==='tomorrow') { at = new Date(now); at.setDate(at.getDate()+1); at.setHours(9,0,0,0); return {at, used:'tomorrow'}; }
  return null;
}
function fmtTime(d){
  const t = d.toLocaleTimeString('en-IN',{hour:'numeric',minute:'2-digit',hour12:true}).replace(/\s?([ap])\.?m\.?/i,(x,a)=>' '+a.toUpperCase()+'M');
  const today = new Date(); const tm = new Date(); tm.setDate(tm.getDate()+1);
  if (d.toDateString()===today.toDateString()) return t + ' today';
  if (d.toDateString()===tm.toDateString()) return t + ' tomorrow';
  return t + ' on ' + d.toLocaleDateString('en-IN',{weekday:'long',day:'numeric',month:'short'});
}

/* ════════════════════════════════════════════════════════
   COMMAND HANDLER
   ════════════════════════════════════════════════════════ */
function handle(text, fromAI){
  setState(S.THINKING);
  let low = text.toLowerCase().trim().replace(/[.!?]+$/,'');
  low = low.replace(new RegExp('^(hey |hi |ok |okay |a )?(' + P.strip.join('|') + ')[, ]+'),'').replace(/^(please|can you|could you|will you)\s+/,'').replace(/\s+please$/,'');
  let m;

  if (SLEEP_WORDS.some(w => low.includes(w)) && low.split(' ').length <= 5) {
    voiceTurn=false; followUp=false; quiz=null; renderModes();
    return speak(opt.lang==='ta' ? L('taSleep',"சரி, தேவைப்படும்போது கூப்பிடுங்க.") : L('sleep',"Alright, I'm here when you need me."), ()=>resumeWake());
  }
  if (STOP_WORDS.includes(low)) { hush(); setState(S.SLEEPING); voiceTurn=false; resumeWake(); return; }

  /* ── language ── */
  if (/\b(speak|talk|reply|answer|switch( to)?|change( to)?)\b.*\btamil\b|^tamil( mode)?$|தமிழ்/.test(low)) return setLanguage('ta');
  if (/\b(speak|talk|reply|answer|switch( to)?|change( to)?)\b.*\benglish\b|^english( mode)?$/.test(low)) return setLanguage('en');

  /* ── quiz ── */
  if (quiz && /\b(stop|end|quit|finish|exit)( the)? quiz\b/.test(low)) {
    const t = quiz.topic; quiz = null; renderModes();
    return think(`The quiz on ${t} is over. Tell me my final score and one thing to revise, in two sentences.`);
  }
  if ((m = low.match(/^(?:quiz me|test me|start (?:a )?quiz|ask me questions)(?:\s+(?:on|about|in|from))?\s*(.*)$/))) {
    const topic = m[1] || 'cell biology';
    quiz = {topic}; renderModes();
    return think(`Start a quiz on ${topic}. Say one short line to kick off, then ask question 1.`);
  }

  /* ── memory ── */
  if ((m = low.match(/^(?:\S+[, ]+){0,2}?(?:please )?(?:remember|note down|note|keep in mind|don't forget)(?: that)?\s+(.{3,})$/)) && !/^(to )?remind/.test(m[1]) && !/^(do|did|can|will) you remember/.test(low)) {
    const fact = text.replace(/^.*?\b(remember|note down|note|keep in mind|don't forget)( that)?\s+/i,'').trim();
    // "tomorrow" means nothing next week: pin relative days to real dates
    const day = off => { const d = new Date(); d.setDate(d.getDate()+off); return d.toLocaleDateString('en-IN',{weekday:'long',day:'numeric',month:'long'}); };
    let f = fact.replace(/\b(on )?day after tomorrow\b/ig, 'on '+day(2)).replace(/\b(on )?tomorrow\b/ig, 'on '+day(1)).replace(/\b(on )?today\b/ig, 'on '+day(0)).replace(/\b(on )?yesterday\b/ig, 'on '+day(-1)).replace(/^on /i,'On ');
    memory.push({text: f.charAt(0).toUpperCase()+f.slice(1), t: Date.now()}); saveMemory();
    return done(L('remember',"Got it. I'll remember that."));
  }
  if (/what do you (remember|know about me)|what have you remembered|show (me )?(your )?memory/.test(low)) {
    if (!memory.length) return done("Nothing yet. Say remember that, and then anything you want me to keep.");
    const list = memory.slice(-6).map(x=>x.text.charAt(0).toUpperCase()+x.text.slice(1)).join('. ');
    return done(`Here's what I remember. ${list}.`);
  }
  if (/^(forget everything|clear (your |my )?memory|wipe (your )?memory)$/.test(low)) {
    memory = []; saveMemory(); return done("Done. My memory is clean.");
  }
  if ((m = low.match(/^forget (?:that |about )?(.+)$/))) {
    const q = m[1]; const before = memory.length;
    memory = memory.filter(x => !x.text.toLowerCase().includes(q)); saveMemory();
    return done(before > memory.length ? "Forgotten." : `I don't have anything about ${q}.`);
  }

  /* ── time / date / battery ── */
  if (/\btime\b/.test(low) && !/remind|alarm|timer|set|schedule|times of|time zone|timezone|in time|on time/.test(low) && low.split(' ').length<=6) {
    return done("It's " + new Date().toLocaleTimeString('en-IN',{hour:'numeric',minute:'2-digit',hour12:true}).replace(/\s?([ap])\.?m\.?/i,(x,a)=>' '+a.toUpperCase()+'M') + ".");
  }
  if (/\b(what('s| is) (the )?date|today'?s date|what day is (it|today))\b/.test(low)) {
    return done("It's " + new Date().toLocaleDateString('en-IN',{weekday:'long',day:'numeric',month:'long',year:'numeric'}) + ".");
  }
  if (/\bbattery\b|\bhow much (charge|charging)\b|\bcharge (left|level)\b/.test(low)) {
    const b = JSON.parse(A.battery());
    return done(`Battery is at ${b.pct} percent${b.charging ? ', and charging' : ''}.`);
  }

  /* ── flashlight ── */
  if (/\b(torch|flash ?light)\b/.test(low) || /\bflash (on|off)\b|(on|off) (the )?flash\b/.test(low)) {
    const off = /\b(off|stop|disable)\b/.test(low);
    return done(A.flashlight(!off) ? (off ? "Torch off." : "Torch on.") : "I couldn't reach the flashlight.");
  }

  /* ── volume / ringer / brightness ── */
  if ((m = low.match(/\bvolume (?:to |at )?(\d{1,3})\s*(?:%|percent)?/))) { const v = A.volume('set', parseInt(m[1])); return done(`Volume at ${v} percent.`); }
  if (/\b(volume up|increase (the )?volume|louder|turn it up|raise (the )?volume)\b/.test(low)) { const v = A.volume('up',0); return done(`Volume up, ${v} percent.`); }
  if (/\b(volume down|decrease (the )?volume|lower (the )?volume|quieter|softer|turn it down)\b/.test(low)) { const v = A.volume('down',0); return done(`Volume down, ${v} percent.`); }
  if (/\b(max(imum)? volume|volume (max|full)|full volume)\b/.test(low)) { A.volume('set',100); return done("Volume at maximum."); }
  if (/^(mute|mute (the )?(phone|media|music|volume|sound))$/.test(low)) { A.volume('mute',0); return done("Muted."); }
  if (/^unmute( (the )?(phone|media|music|volume|sound))?$/.test(low)) { A.volume('unmute',0); return done("Unmuted."); }
  if ((m = low.match(/\b(silent|vibrate|vibration|normal|ring|ringer) mode\b|\bput (?:the )?phone on (silent|vibrate)\b/))) {
    const want = (m[1]||m[2]); const mode = /silent/.test(want) ? 'silent' : /vib/.test(want) ? 'vibrate' : 'normal';
    const r = A.ringer(mode);
    if (r==='needs') return done("Android needs you to allow Do Not Disturb access for Echo. I've opened that page.");
    return done(r==='ok' ? `Phone on ${mode==='normal'?'ring':mode}.` : "Your phone didn't let me change that.");
  }
  if (/\bbrightness\b|\b(dim|brighten) (the )?screen\b/.test(low)) {
    let action = 'set', val = 50;
    if ((m = low.match(/(\d{1,3})\s*(%|percent)?/))) val = parseInt(m[1]);
    else if (/\b(up|increase|raise|brighter|brighten)\b/.test(low)) action = 'up';
    else if (/\b(down|decrease|lower|dim|darker)\b/.test(low)) action = 'down';
    else if (/\b(max|full|maximum)\b/.test(low)) val = 100;
    else if (/\b(min|minimum|lowest)\b/.test(low)) val = 3;
    else if (/\bauto\b/.test(low)) action = 'auto';
    const r = A.brightness(action, val);
    if (r==='needs') return done("First, allow Echo to modify system settings. I've opened that page. Then ask me again.");
    return done(r==='ok' ? "Brightness set." : "Your phone didn't let me change the brightness.");
  }
  if ((m = low.match(/\b(?:turn|switch|put) (on|off) (?:the )?(wi-?fi|wi fi|bluetooth|mobile data|data|internet|hotspot|location)\b|\b(wi-?fi|wi fi|bluetooth|mobile data|hotspot|location) (on|off)\b/))) {
    const what = (m[2]||m[3]).replace(/wi.?fi/,'wifi');
    const panel = /data|internet/.test(what) ? 'internet' : what;
    A.openSettings(panel);
    return done(`Android only lets apps open the ${what} switch. It's on your screen, just tap it.`);
  }

  /* ── timers & alarms ── */
  if ((m = low.match(/timer (?:for )?(\d+|an?|one|two|three|five|ten|fifteen|twenty|thirty)\s*(second|sec|minute|min|hour|hr)s?/))) {
    const n = num(m[1]), u = m[2];
    const secs = n * (u.startsWith('h') ? 3600 : u.startsWith('m') ? 60 : 1);
    const unit = u.startsWith('h') ? 'hour' : u.startsWith('m') ? 'minute' : 'second';
    return done(A.timer(secs, 'Echo timer') ? `Timer set for ${n} ${unit}${n>1?'s':''}.` : "Your clock app didn't accept the timer.");
  }
  if (/\b(alarm|wake me)\b/.test(low) && !/\bremind\b/.test(low)) {
    const w = parseWhen(low.replace(/\bfor\b/,'at'));
    if (!w || !w.at) return done("What time? Try: set an alarm for 6 30 AM.");
    const ok = A.alarm(w.at.getHours(), w.at.getMinutes(), 'Echo');
    return done(ok ? `Alarm set for ${fmtTime(w.at)}.` : "Your clock app didn't accept the alarm.");
  }

  /* ── reminders ── */
  if (/\b(what are|show|list|read) (my )?reminders\b|\bany reminders\b/.test(low)) {
    const r = JSON.parse(A.reminders());
    if (!r.length) return done("You have no reminders set.");
    return done(`You have ${r.length}. ` + r.sort((a,b)=>a.at-b.at).slice(0,5).map(x => `${fmtTime(new Date(x.at))}, ${x.text}`).join('. ') + '.');
  }
  if (/\b(cancel|clear|delete|remove) (all )?(my )?reminders\b/.test(low)) { A.clearReminders(); return done("All reminders cleared."); }
  if (/^(remind me|set (a )?reminder|reminder)\b/.test(low)) {
    let body = low.replace(/^(remind me|set (a )?reminder|reminder)\s*(to |for |about |that )?/,'');
    const w = parseWhen(body);
    if (!w || !w.at) {
      const task = body.trim();
      return ask(task ? `When should I remind you to ${task}?` : "What should I remind you about, and when?", 'Say a time, like "at 6 PM" or "in 20 minutes"', (ans)=>{
        handle('remind me ' + (task ? 'to ' + task + ' ' : '') + ans.toLowerCase());
      });
    }
    let task = body.replace(w.used, ' ').replace(/\b(today|tomorrow|tonight|day after tomorrow)\b/,' ')
      .replace(/^\s*(to|about|that|for)\s+/,'').replace(/\s+(to|about|that)\s*$/,'').replace(/\s{2,}/g,' ').trim()
      .replace(/^(to|about|that)\s+/,'');
    if (!task) task = 'Reminder from Echo';
    A.remind(+w.at, task.charAt(0).toUpperCase() + task.slice(1));
    return done(`Okay. At ${fmtTime(w.at)}, I'll remind you to ${task.replace(/^my /,'your ')}.`);
  }

  /* ── notifications ── */
  if (/\b(read|check|any|what are|show)\b.*\b(notifications?|messages?|whatsapps?|texts?)\b/.test(low) && !/^(send|message|whatsapp|text)\b/.test(low)) {
    return readNotifications();
  }

  /* ── messages ── */
  if ((m = low.match(/^(?:send (?:a )?(?:whatsapp |text )?(?:message|msg|text)? ?to|send whatsapp to|whatsapp|message|msg|text|sms|tell)\s+(.+)$/)) && !/^(tell me|tell us)/.test(low) && !/^tell (my )?(laptop|pc|computer)/.test(low)) {
    const channel = /^(text|sms|send (a )?text)/.test(low) ? 'sms' : 'whatsapp';
    // keep Paul's original casing for the message text
    const raw = text.replace(/^[^a-z]*?(send (a )?(whatsapp |text )?(message|msg|text)? ?to|send whatsapp to|whatsapp|message|msg|text|sms|tell)\s+/i,'');
    return startMessage(raw, channel);
  }

  /* ── calls ── */
  if ((m = low.match(/^(?:call|dial|phone|ring)\s+(?:to\s+)?(.+?)(?:\s+(?:now|please))?$/))) {
    const target = m[1].trim();
    const digits = target.replace(/[^\d+]/g,'');
    if (digits.replace('+','').length >= 3 && /^[\d\s+\-()]+$/.test(target)) return callNow(digits, target);
    const cb = 'k'+Date.now();
    pending[cb] = {kind:'call', target};
    A.contact(target, cb);
    return;
  }

  /* ── market ── */
  if (/\b(bof|breakout|break out|signals?|scan (the )?market|market scan|any setups?)\b/.test(low) ||
      /\b(nifty|bank ?nifty|sensex|gold|bitcoin|btc|crude)\b.*\b(price|now|at|doing|level)\b|\b(price of|how is|how's|where is) (the )?(nifty|bank ?nifty|sensex|gold|bitcoin|btc|crude)\b/.test(low)) {
    return scanMarket(low);
  }

  /* ── laptop ── */
  if ((m = low.match(/^(?:on |in )?(?:my )?(?:laptop|pc|computer)[,:]?\s+(.+)$/)) || (m = low.match(/^tell (?:my )?(?:laptop|pc|computer)(?: to)?\s+(.+)$/))) {
    return laptopCommand(m[1]);
  }

  /* ── photos ── */
  if ((/^(what('s| is) this|explain this|look at this|scan this|read this|solve this|identify this|check this|take a (photo|picture)|what am i looking at)\b/.test(low) && low.split(' ').length <= 5) ||
      /\b(this|a) (photo|picture|image|diagram|question|page)\b/.test(low) && /\b(explain|solve|read|what|look|check|identify|describe)\b/.test(low)) {
    return startPhoto('camera', /^(take a (photo|picture))$/.test(low) ? '' : text);
  }

  /* ── play ── */
  if ((m = low.match(/^play\s+(.+?)(?:\s+on\s+(youtube|spotify|yt))?$/))) {
    const where = m[2]==='spotify' ? 'spotify' : 'youtube';
    A.play(m[1], where);
    return done(`Playing ${m[1]} on ${where==='spotify'?'Spotify':'YouTube'}.`);
  }
  if ((m = low.match(/^(?:youtube|search youtube for|search on youtube for|search youtube)\s+(.+)$/))) {
    A.openUrl('https://www.youtube.com/results?search_query='+encodeURIComponent(m[1]));
    return done(`Here's YouTube for ${m[1]}.`);
  }

  /* ── search ── */
  if ((m = low.match(/^(?:search for|search|google|look up)\s+(.+)$/))) {
    A.openUrl('https://www.google.com/search?q='+encodeURIComponent(m[1]));
    return done(`Searching for ${m[1]}.`);
  }

  /* ── open ── */
  if ((m = low.match(/^(?:open|launch|start|go to)\s+(?:the\s+|my\s+)?(.+?)(?:\s+app)?$/))) {
    const target = m[1].trim();
    const sKey = Object.keys(SETTINGS_WORDS).find(k => target === k || target === k+' settings');
    if (sKey) { A.openSettings(SETTINGS_WORDS[sKey]); return done(sKey==='settings' ? "Opening settings." : `Opening ${sKey} settings.`); }
    const name = APP_ALIASES[target] || target;
    const opened = A.openApp(name) || (name!==target ? A.openApp(target) : '');
    if (opened) return done(`Opening ${opened}.`);
    const url = WEB_APPS[target] || WEB_APPS[name];
    if (url) { A.openUrl(url); return done(`Opening ${target}.`); }
    if (/\.[a-z]{2,}$/.test(target)) { A.openUrl('https://'+target.replace(/\s/g,'')); return done(`Opening ${target}.`); }
    return done(`I couldn't find ${target} on your phone.`);
  }

  /* ── research ── */
  if ((m = low.match(/^research\s+(.+)$/))) {
    return think(`Research this and give me a short spoken briefing, three to five sentences, with the key facts: ${m[1]}`, true);
  }

  if (fromAI) return done("I understood what you want, but I couldn't do that one.");

  /* ── everything else: the brain ── */
  const fresh = /\b(news|weather|today|tonight|tomorrow|yesterday|latest|current|currently|right now|score|price|rate|who won|this week|recent|20\d\d)\b/.test(low);
  think(text, fresh);
}

/* ── language ── */
function setLanguage(l){
  opt.lang = l; setPref('lang', l);
  const ok = A.setLang(l==='ta' ? 'ta-IN' : 'en-IN');
  renderModes(); syncUi();
  if (l==='ta') {
    if (!ok) log('Your phone has no Tamil voice yet, so Echo will write Tamil but speak it poorly. Add one in ' + link('text-to-speech settings',"A.openSettings('voice')") + ' → Google → Install voice data → Tamil.', 'sys', false);
    return done("சரி Paul, இனிமே தமிழ்ல பேசுறேன்.");
  }
  return done("Okay, back to English.");
}

/* ── calls ── */
function callNow(number, spokenName){
  voiceTurn = false; followUp = false;
  speak(`Calling ${spokenName}.`, ()=> A.call(number));
}

/* ── messages ── */
function startMessage(raw, channel){
  const cb = 'k'+Date.now();
  pending[cb] = {kind:'message', channel, raw};
  A.contactSplit(raw, cb);
}
function sendMessage(name, number, body, channel){
  const app = channel==='sms' ? 'Messages' : 'WhatsApp';
  voiceTurn = false;
  speak(`Opening ${app} to ${name}. Just tap send.`, ()=>{
    const ok = channel==='sms' ? A.sms(number, body) : A.whatsapp(number, body);
    if (!ok) log(`Couldn't open ${app}.`, 'err', false);
    resumeWake();
  });
}
window.onContact = function(cbId, r){
  const p = pending[cbId]; if (!p) return;
  delete pending[cbId];
  if (r.status === 'denied') {
    log('Echo needs Contacts access to find people. ' + link('Allow it',"A.requestPerm('contacts')") + '.', 'err', false);
    return done("I need permission to see your contacts for that.");
  }
  if (p.kind === 'call') {
    if (r.status === 'found') return callNow(r.number, r.name);
    return done(`I couldn't find ${p.target} in your contacts.`);
  }
  if (p.kind === 'message') {
    if (r.status !== 'found') return done(`I couldn't find who that's for. Say it like: message Tharun I'm on the way.`);
    const first = r.name.split(' ')[0];
    const body = (r.rest||'').trim();
    if (!body) {
      return ask(`What should I say to ${first}?`, `Your message to ${first}…`, (ans)=> sendMessage(r.name, r.number, ans, p.channel));
    }
    // keep original casing from what Paul typed/said
    const idx = p.raw.toLowerCase().indexOf(body.toLowerCase());
    return sendMessage(r.name, r.number, idx >= 0 ? p.raw.slice(idx, idx+body.length) : body, p.channel);
  }
};

/* ── notifications ── */
function readNotifications(){
  // Google Play Protect blocks sideloaded apps that read notifications (anti-OTP-scam rule in India),
  // so Echo can't have this one.
  return done("I can't read notifications. Google blocks that for apps installed outside the Play Store.");
}

/* ── market ── */
let lastBof = null;
function scanMarket(low){
  const priceAsk = /\b(price|how is|how's|where is|doing|level)\b/.test(low) && !/\b(bof|breakout|signal)/.test(low);
  if (lastBof && Date.now() - lastBof.at < 60e3) return marketReply(lastBof.data, low, priceAsk);
  const cb = 'm'+Date.now();
  pending[cb] = {kind:'bof', low, priceAsk};
  setState(S.THINKING);
  hint('Scanning the markets…');
  A.bof(cb);
}
window.onBof = function(cbId, r){
  const p = pending[cbId]; if (!p) return;
  delete pending[cbId];
  lastBof = {at: Date.now(), data: r};
  marketReply(r, p.low, p.priceAsk);
};
const LEVEL_WORDS = {PDH:'previous day high',PDL:'previous day low',ORH:'opening range high',ORL:'opening range low',
  H3:'Camarilla H3',H4:'Camarilla H4',L3:'Camarilla L3',L4:'Camarilla L4','Swing H':'swing high','Swing L':'swing low'};
function clock(t){ return new Date(t).toLocaleTimeString('en-IN',{hour:'numeric',minute:'2-digit',hour12:true}).replace(/\s?([ap])\.?m\.?/i,(x,a)=>' '+a.toUpperCase()+'M'); }
function fmtNum(n){ return Number(n).toLocaleString('en-IN',{maximumFractionDigits: Math.abs(n) < 10 ? 4 : Math.abs(n) < 1000 ? 2 : 1}); }
function marketReply(r, low, priceAsk){
  if (!r.prices || !r.prices.length) return done("I couldn't reach the market data. Check your internet.");
  const keyMap = {nifty:'NIFTY','bank nifty':'BANKNIFTY',banknifty:'BANKNIFTY',sensex:'SENSEX',gold:'GOLD',bitcoin:'BTC',btc:'BTC',crude:'CRUDE'};
  if (priceAsk) {
    const k = Object.keys(keyMap).sort((a,b)=>b.length-a.length).find(k => low.includes(k));
    const p = k && r.prices.find(x => x.name === keyMap[k]);
    if (p) {
      const ch = p.prev ? (p.price - p.prev) / p.prev * 100 : 0;
      return done(`${MARKET_NAMES[p.name]} is at ${fmtNum(p.price)}, ${ch>=0?'up':'down'} ${Math.abs(ch).toFixed(2)} percent${p.open?'':' at the last close'}.`);
    }
  }
  const sigs = (r.signals||[]).filter(s => s.score >= 3).sort((a,b)=>b.t-a.t);
  if (sigs.length) {
    const rows = sigs.slice(0,6).map(s => `<div class="sig"><span class="n">${esc(MARKET_NAMES[s.name]||s.name)}</span><span class="d ${s.bull?'b':'s'}">${s.bull?'BULL':'BEAR'} · ${esc(s.level)}</span><span class="x">${s.score}/6 · ${clock(s.t)}</span></div>`).join('');
    log(rows, 'card', false);
  }
  if (!sigs.length) {
    const nifty = r.prices.find(x=>x.name==='NIFTY');
    return done(`No breakout failures with a score of 3 or more today.${nifty ? ' Nifty is at ' + fmtNum(nifty.price) + '.' : ''}`);
  }
  const say = sigs.slice(0,3).map(s => `${MARKET_NAMES[s.name]||s.name}, ${s.bull?'bullish':'bearish'} failure at the ${LEVEL_WORDS[s.level]||s.level}, ${s.score} out of 6, at ${clock(s.t)}`).join('. ');
  return done(`${sigs.length} signal${sigs.length>1?'s':''} today. ${say}.`);
}

/* ── laptop ── */
function laptopCommand(cmd){
  if (!pref('laptopHost','')) {
    log('Set your laptop address and PIN in ' + link('settings','openSettings()') + '. Desktop Echo shows both when it starts.', 'sys', false);
    return done("I don't know your laptop's address yet. Add it in settings.");
  }
  const cb = 'l'+Date.now();
  pending[cb] = {kind:'laptop', cmd};
  A.laptop('/cmd', JSON.stringify({pin: pref('laptopPin',''), text: cmd}), cb);
}
window.onLaptop = function(cbId, r){
  const p = pending[cbId]; if (!p) return;
  delete pending[cbId];
  if (p.kind === 'test') {
    if (r.code === 200) { toast('Laptop connected'); return; }
    return toast(r.code === 403 ? 'Wrong PIN' : "Can't reach the laptop");
  }
  if (r.code === 200) return done(`Done on your laptop: ${p.cmd}.`);
  if (r.code === 403) return done("The laptop says the PIN is wrong. Check it in settings.");
  return done("I can't reach your laptop. Make sure desktop Echo is open and you're on the same Wi-Fi.");
};

/* ── photos ── */
let photoQuestion = '';
function startPhoto(source, question){
  const typed = $('entry').value.trim();
  photoQuestion = question || typed;
  if (typed) { if (!question) log(typed, 'user'); $('entry').value=''; onEntry(); }
  const cb = 'p'+Date.now();
  pending[cb] = {kind:'photo'};
  hush(); setState(S.THINKING); hint(source==='camera' ? 'Opening the camera…' : 'Pick a photo…');
  A.photo(source, cb);
}
window.onPhoto = function(cbId, r){
  const p = pending[cbId]; if (!p) return;
  delete pending[cbId];
  if (!r.ok) {
    setState(S.SLEEPING);
    if (r.error !== 'cancelled') log(r.error==='nocamera' ? 'No camera app found.' : "Couldn't read that photo.", 'err', false);
    resumeWake(); return;
  }
  log(r.thumb, 'img', false);
  const q = photoQuestion;
  photoQuestion = '';
  const prompt = (q ? q + ' ' : '') + "Look at this photo. " + (q ? "Answer my question about it." :
    "Tell me what it is. If it's a question, diagram or page from my studies, explain it clearly and simply, and solve it if it's a problem.") +
    " You can use up to six sentences for this one.";
  history.push({role:'user', parts:[{text: prompt}]});
  trimHistory();
  const cb = 'c'+Date.now();
  pending[cb] = {kind:'brain'};
  setState(S.THINKING);
  A.askImage(systemPrompt(), JSON.stringify(history), cb);
};

/* ════════════════════════════════════════════════════════
   THE BRAIN
   ════════════════════════════════════════════════════════ */
function think(prompt, search){
  setState(S.THINKING);
  history.push({role:'user', parts:[{text:prompt}]});
  trimHistory();
  const cb = 'c'+Date.now();
  pending[cb] = {kind:'brain'};
  A.ask(systemPrompt(), JSON.stringify(history), !!search, cb);
}
function trimHistory(){
  if (history.length > CONTEXT_TURNS) history = history.slice(-CONTEXT_TURNS);
  while (history.length && history[0].role !== 'user') history.shift();
}
function cleanReply(t){
  return t.replace(/<think>[\s\S]*?<\/think>/g,'')
          .replace(/```[\s\S]*?```/g,'')
          .replace(/\[(.*?)\]\((.*?)\)/g,'$1')
          .replace(/^\s*[-*•]\s+/gm,'')
          .replace(/^\s*#+\s*/gm,'')
          .replace(/[*_`#]/g,'')
          .replace(/\n{3,}/g,'\n\n')
          .trim();
}
window.onReply = function(cbId, r){
  const p = pending[cbId]; if (!p) return;
  delete pending[cbId];
  if (r.error) {
    history.pop();
    const msgs = {
      NO_KEY: ["I don't have a brain key yet.", 'Add your Gemini key in ' + link('settings','openSettings()') + '.'],
      BAD_KEY: ["That Gemini key didn't work.", 'Check it in ' + link('settings','openSettings()') + ', or make a new one at <a href="https://aistudio.google.com/apikey">aistudio.google.com/apikey</a>.'],
      NETWORK: ["I can't reach the internet right now.", 'Check your mobile data or Wi-Fi.'],
      BLOCKED: ["I can't help with that one.", ''],
      EMPTY: ["I drew a blank on that. Try asking another way.", '']
    };
    let [say, extra] = msgs[r.error] || (r.code===429
      ? ["Gemini says I'm over the free limit right now. Try again in a minute.", 'Gemini free-tier limit. Google says: <i>' + esc(String(r.error)).slice(0,220) + '</i>']
      : ["Something went wrong reaching my brain.", esc(String(r.error)).slice(0,160)]);
    if (extra) log(extra, 'err', false);
    return speak(say, ()=>{ voiceTurn=false; resumeWake(); });
  }
  let raw = r.text || '';
  const act = raw.match(/<<\s*do:\s*([^>]+?)\s*>>/i);
  if (act) {
    history.push({role:'model', parts:[{text: raw}]}); trimHistory(); saveChat();
    return handle(act[1], true);
  }
  const text = cleanReply(raw);
  history.push({role:'model', parts:[{text}]});
  trimHistory(); saveChat();
  reply(text || "Hmm, I've got nothing on that.");
};

/* ════════════════════════════════════════════════════════
   ORB — a living, breathing sonar
   ════════════════════════════════════════════════════════ */
const cv = $('orbCanvas'), cx = cv.getContext('2d');
let W=0,H=0,dpr=1, t0=performance.now(), shown=0, hueMix=0;
const parts = Array.from({length:46}, (_,i)=>({a:Math.random()*Math.PI*2, r:0.9+Math.random()*0.9, s:(0.15+Math.random()*0.35)*(Math.random()<.5?-1:1), z:Math.random()}));
function resize(){ dpr = window.devicePixelRatio||1; W = cv.clientWidth; H = cv.clientHeight; cv.width=W*dpr; cv.height=H*dpr; }
window.addEventListener('resize', resize); resize();
const COLS = {standby:[[47,224,208],[124,92,255]], wake:[[47,224,208],[60,120,255]], listening:[[255,180,84],[255,107,139]], thinking:[[124,92,255],[47,224,208]], speaking:[[47,224,208],[124,92,255]]};
if (P.orb) Object.assign(COLS, P.orb);
let curA=COLS.standby[0].slice(), curB=COLS.standby[1].slice();
function mix(a,b,k){ return a.map((v,i)=>v+(b[i]-v)*k); }
function rgba(c,a){ return `rgba(${c[0]|0},${c[1]|0},${c[2]|0},${a})`; }
function draw(now){
  const t = (now - t0)/1000;
  cx.setTransform(dpr,0,0,dpr,0,0);
  cx.clearRect(0,0,W,H);
  const x = W/2, y = H/2 - 4;
  const [ta, tb] = COLS[state] || COLS.standby;
  curA = mix(curA, ta, 0.06); curB = mix(curB, tb, 0.06);
  const active = state!==S.SLEEPING;
  const target = state===S.LISTENING ? level : state===S.SPEAKING ? 0.35+0.3*Math.abs(Math.sin(t*7.3)*Math.sin(t*2.9)) : state===S.THINKING ? 0.18 : 0;
  shown += (target - shown) * 0.18;
  const base = Math.min(W,H)*0.19;

  // sonar ripples
  const n = 4, speed = state===S.THINKING ? 0.8 : active ? 0.5 : 0.2;
  for (let i=0;i<n;i++){
    const p = ((t*speed + i/n) % 1);
    const r = base*1.05 + p * Math.min(W,H)*0.34;
    cx.beginPath(); cx.arc(x,y,r,0,Math.PI*2);
    cx.strokeStyle = rgba(curA, (1-p)*(active?0.42:0.14)); cx.lineWidth = 1.2; cx.stroke();
  }
  // halo
  const R = base * (1 + shown*0.28 + 0.025*Math.sin(t*1.7));
  let g = cx.createRadialGradient(x,y,R*0.3,x,y,R*2.4);
  g.addColorStop(0, rgba(curA, active?0.32:0.18)); g.addColorStop(0.5, rgba(curB, active?0.12:0.06)); g.addColorStop(1, rgba(curB,0));
  cx.fillStyle = g; cx.beginPath(); cx.arc(x,y,R*2.4,0,Math.PI*2); cx.fill();

  // particles
  for (const q of parts){
    q.a += q.s * 0.012 * (active?1.8:1);
    const rr = R*(1.25 + q.r*0.55 + (state===S.THINKING?0.15*Math.sin(t*2+q.z*6):0));
    const px = x + Math.cos(q.a)*rr, py = y + Math.sin(q.a)*rr*0.92;
    cx.fillStyle = rgba(q.z>.5?curA:curB, 0.25 + 0.5*q.z*(active?1:0.5));
    cx.beginPath(); cx.arc(px,py, 0.6+q.z*1.4, 0, Math.PI*2); cx.fill();
  }

  // morphing blob
  const steps = 90;
  cx.beginPath();
  for (let i=0;i<=steps;i++){
    const a = i/steps*Math.PI*2;
    const wob = 0.045*Math.sin(3*a + t*1.3) + 0.035*Math.sin(5*a - t*1.7) + 0.025*Math.sin(2*a + t*0.7)
              + shown*0.12*Math.sin(7*a + t*9) + (state===S.THINKING ? 0.05*Math.sin(4*a - t*4) : 0);
    const rr = R*(1 + wob);
    const px = x + Math.cos(a)*rr, py = y + Math.sin(a)*rr;
    i ? cx.lineTo(px,py) : cx.moveTo(px,py);
  }
  cx.closePath();
  g = cx.createRadialGradient(x-R*0.35, y-R*0.4, R*0.1, x, y, R*1.15);
  g.addColorStop(0, rgba(mix(curA,[255,255,255],0.75), 1));
  g.addColorStop(0.35, rgba(curA, active?0.98:0.85));
  g.addColorStop(0.8, rgba(curB, active?0.95:0.8));
  g.addColorStop(1, rgba(mix(curB,[5,7,13],0.5), 0.95));
  cx.fillStyle = g; cx.fill();
  // glass rim + highlight
  cx.strokeStyle = 'rgba(255,255,255,.18)'; cx.lineWidth = 1; cx.stroke();
  cx.beginPath(); cx.ellipse(x-R*0.28, y-R*0.42, R*0.32, R*0.16, -0.5, 0, Math.PI*2);
  cx.fillStyle = 'rgba(255,255,255,.22)'; cx.fill();
  // thinking arcs
  if (state===S.THINKING){
    cx.lineCap='round'; cx.lineWidth = 2.4;
    cx.beginPath(); cx.arc(x,y,R*1.3, t*3, t*3+1.2); cx.strokeStyle = rgba(curA,.85); cx.stroke();
    cx.beginPath(); cx.arc(x,y,R*1.3, t*3+Math.PI, t*3+Math.PI+0.5); cx.strokeStyle = rgba(curB,.8); cx.stroke();
  }
  requestAnimationFrame(draw);
}
requestAnimationFrame(draw);

/* ════════════════════════════════════════════════════════
   UI WIRING
   ════════════════════════════════════════════════════════ */
function talkTap(){
  if (state===S.SPEAKING) { hush(); voiceTurn=false; followUp=false; setState(S.SLEEPING); resumeWake(); return; }
  if (state===S.LISTENING) { A.stopListen(); followUp=false; awaiting=null; setState(S.SLEEPING); resumeWake(); return; }
  if (state===S.THINKING) return;
  followUp = false;
  listenForCommand();
}
$('orb').onclick = talkTap;
$('mic').onclick = talkTap;

const entry = $('entry');
function onEntry(){ const has = entry.value.trim().length>0; $('send').style.display = has?'grid':'none'; $('mic').style.display = has?'none':'grid'; }
entry.addEventListener('input', onEntry);
function sendText(){
  const t = entry.value.trim(); if (!t) return;
  entry.value=''; onEntry();
  if (state===S.SPEAKING) hush();
  voiceTurn = false; followUp = false;
  submit(t);
}
$('send').onclick = sendText;
entry.addEventListener('keydown', e => { if (e.key==='Enter') { e.preventDefault(); sendText(); } });

/* quick actions */
document.querySelectorAll('.qa').forEach(b => b.onclick = ()=>{
  const q = b.dataset.q;
  if (q==='photo') return $('photoSheet').classList.add('show');
  if (q==='quiz') { entry.value = 'quiz me on '; onEntry(); entry.focus(); return; }
  if (q==='remind') { entry.value = 'remind me at '; onEntry(); entry.focus(); return; }
  if (q==='message') { entry.value = 'message '; onEntry(); entry.focus(); return; }
  if (q==='news') { voiceTurn=false; log('Read my notifications', 'user'); return readNotifications(); }
  if (q==='bof') { voiceTurn=false; log('Any BOF signals today?', 'user'); return scanMarket('bof signals'); }
  if (q==='laptop') { entry.value = 'laptop '; onEntry(); entry.focus(); return; }
  if (q==='hf') { opt.hf = !opt.hf; setPref('hf', opt.hf?1:0); syncUi(); applyHf(); }
});
$('camBtn').onclick = ()=> $('photoSheet').classList.add('show');
$('pCam').onclick = ()=>{ closeSheets(); startPhoto('camera',''); };
$('pGal').onclick = ()=>{ closeSheets(); startPhoto('gallery',''); };

function applyHf(){
  if (opt.hf) {
    if (!A.hasMicPermission()) A.requestPerm('mic');
    toast('Say "Hey Echo" while the app is open');
    if (state===S.SLEEPING) startWakeLoop(300);
  } else {
    clearTimeout(hfTimer);
    if (state===S.WAKE) { A.stopListen(); setState(S.SLEEPING); }
    toast('Hands-free off');
  }
  setState(state);
}

/* ── one-touch power: everything that listens, on or off ── */
function listeningOn(){ return opt.hf || A.backgroundRunning(); }
function renderPower(){
  const on = listeningOn();
  $('powerBtn').classList.toggle('on', on);
  $('powerBtn').setAttribute('aria-pressed', on);
}
function powerToggle(){
  if (listeningOn()) {
    A.setBackground(false); setPref('bg', 0);
    opt.hf = false; setPref('hf', 0);
    clearTimeout(hfTimer); A.stopListen();
    if (state===S.WAKE || state===S.LISTENING) setState(S.SLEEPING);
    toast('Echo is off. Nothing is listening. Tap ⏻ to turn her on.');
  } else {
    opt.hf = true; setPref('hf', 1);
    const ok = A.setBackground(true);
    setPref('bg', ok ? 1 : 0);
    toast(ok ? 'Echo is on. Say "Hey Echo", even with the screen off.' : 'Allow the microphone, then tap ⏻ again.');
    if (state===S.SLEEPING) startWakeLoop(400);
  }
  syncUi(); setState(state);
  setTimeout(renderPower, 600);
}
$('powerBtn').onclick = powerToggle;

function renderModes(){
  const m = $('modes'); m.innerHTML = '';
  $('greet').style.display = (quiz || opt.lang==='ta') ? 'none' : '';
  if (quiz) m.innerHTML += `<div class="mode">QUIZ · ${esc(quiz.topic.toUpperCase())} <button onclick="handle('stop quiz')" aria-label="End quiz">×</button></div>`;
  if (opt.lang==='ta') m.innerHTML += `<div class="mode ta">தமிழ் <button onclick="setLanguage('en')" aria-label="Back to English">×</button></div>`;
}

function syncUi(){
  if ($('powerBtn')) setTimeout(renderPower, 300);
  $('hfChip').classList.toggle('on', opt.hf);
  $('optVoice').checked = opt.voice; $('optFollow').checked = opt.follow; $('optHf').checked = opt.hf;
  $('optBg').checked = pref('bg','0')==='1' && A.backgroundRunning();
  $('rate').value = opt.rate; $('pitch').value = opt.pitch;
  document.querySelectorAll('#langSeg button').forEach(b => b.classList.toggle('on', b.dataset.l===opt.lang));
}

/* permissions list in settings */
const PERM_ROWS = [
  ['mic','Microphone','To hear you'],
  ['contacts','Contacts','Call and message people by name'],
  ['phone','Phone calls','Place calls without the dial pad'],
  ['notify','Notifications','Reminders and the "Hey Echo" pop-up'],
  ['overlay','Display over other apps','Pop up when you say "Hey Echo" with the screen off'],
  ['writeSettings','Modify system settings','Change brightness by voice'],
  ['exactAlarm','Exact reminders','Reminders on the dot']
];
function renderPerms(){
  let p = {};
  try { p = JSON.parse(A.perms()); } catch(e){}
  $('permList').innerHTML = PERM_ROWS.map(([k,t,s]) =>
    `<div class="li"><div class="grow">${t}<div class="sub">${s}</div></div>${p[k] ? '<span class="st ok">ALLOWED</span>' : `<button class="st go" onclick="A.requestPerm('${k}')">ALLOW</button>`}</div>`).join('');
  if ($('optBg')) $('optBg').checked = !!p.bg;
}

/* sheets */
function closeSheets(){ document.querySelectorAll('.sheet.show').forEach(s => s.classList.remove('show')); }
document.querySelectorAll('.sheet').forEach(s => s.addEventListener('click', e => { if (e.target===s && s.id!=='setup') { if (s.id==='settings') $('closeSet').click(); else s.classList.remove('show'); } }));
document.querySelectorAll('[data-open]').forEach(b => b.onclick = ()=> A.openSettings(b.dataset.open));

function openSettings(){
  $('keyEdit').value = A.getPref('apikey');
  $('modelEdit').value = A.getPref('model');
  const lm = A.getPref('lastModel');
  $('modelInfo').textContent = lm ? 'Last used: ' + lm : '';
  $('lapHost').value = pref('laptopHost',''); $('lapPin').value = pref('laptopPin','');
  syncUi(); renderPerms();
  $('settings').classList.add('show');
}
$('gear').onclick = openSettings;
$('langSeg').onclick = e => { const b = e.target.closest('button'); if (b && b.dataset.l !== opt.lang) { $('settings').classList.remove('show'); setLanguage(b.dataset.l); } };
$('optBg').onchange = e => {
  const on = e.target.checked;
  const ok = A.setBackground(on);
  setPref('bg', on?1:0);
  if (on && ok) {
    let p = {}; try { p = JSON.parse(A.perms()); } catch(_){}
    toast('Always listening is on. Look for the Echo notification.');
    if (!p.overlay) log('So Echo can pop up when you say "Hey Echo" with the screen off, ' + link('allow Display over other apps',"A.requestPerm('overlay')") + '.', 'sys', false);
  } else if (on && !ok) { e.target.checked = false; toast('Allow the microphone first'); }
};
$('lapTest').onclick = ()=>{
  setPref('laptopHost', $('lapHost').value.trim()); setPref('laptopPin', $('lapPin').value.trim());
  const cb = 't'+Date.now(); pending[cb] = {kind:'test'};
  toast('Checking…');
  A.laptop('/ping', JSON.stringify({pin: $('lapPin').value.trim()}), cb);
};
$('closeSet').onclick = ()=>{
  const k = $('keyEdit').value.trim(); if (k) A.setPref('apikey', k);
  A.setPref('model', $('modelEdit').value.trim());
  opt.voice = $('optVoice').checked; setPref('voice', opt.voice?1:0);
  opt.follow = $('optFollow').checked; setPref('follow', opt.follow?1:0);
  const hf = $('optHf').checked; if (hf !== opt.hf) { opt.hf = hf; setPref('hf', hf?1:0); applyHf(); }
  opt.rate = parseFloat($('rate').value); A.setRate(opt.rate); setPref('rate', opt.rate);
  opt.pitch = parseFloat($('pitch').value); A.setPitch(opt.pitch); setPref('pitch', opt.pitch);
  setPref('laptopHost', $('lapHost').value.trim()); setPref('laptopPin', $('lapPin').value.trim());
  syncUi();
  $('settings').classList.remove('show');
};
$('clear').onclick = ()=>{
  history = []; saved = []; saveChat(); logEl.innerHTML=''; quiz=null; renderModes();
  $('settings').classList.remove('show');
  log('Conversation cleared.', 'sys', false);
};

/* memory sheet */
const X_SVG = '<svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6 6 18"/></svg>';
function renderMemory(){
  $('memList').innerHTML = memory.length ? memory.map((m,i)=>`<div class="li"><div class="grow">${esc(m.text)}</div><button class="x" onclick="forgetAt(${i})" aria-label="Forget">${X_SVG}</button></div>`).join('')
    : '<div class="empty">Nothing yet. Try: "remember that my exam is on October 14".</div>';
  let r = []; try { r = JSON.parse(A.reminders()); } catch(e){}
  r.sort((a,b)=>a.at-b.at);
  $('remList').innerHTML = r.length ? r.map(x=>`<div class="li"><div class="grow">${esc(x.text)}<div class="sub">${fmtTime(new Date(x.at))}</div></div><button class="x" onclick="A.removeReminder(${x.id});renderMemory()" aria-label="Cancel">${X_SVG}</button></div>`).join('')
    : '<div class="empty">No reminders. Try: "remind me in 20 minutes to drink water".</div>';
}
function forgetAt(i){ memory.splice(i,1); saveMemory(); renderMemory(); }
$('memBtn').onclick = ()=>{ renderMemory(); $('memSheet').classList.add('show'); };
$('memClose').onclick = ()=> $('memSheet').classList.remove('show');

/* first run */
$('saveKey').onclick = ()=>{
  const k = $('keyIn').value.trim();
  if (k.length < 20) { $('setupMsg').textContent = "That doesn't look like a full key. It usually starts with AIza."; return; }
  A.setPref('apikey', k);
  $('setup').classList.remove('show');
  greet(true);
};

function greeting(){
  const h = new Date().getHours();
  return T(h < 5 ? 'Up late, Paul' : h < 12 ? 'Good morning, Paul' : h < 17 ? 'Good afternoon, Paul' : 'Good evening, Paul');
}
function greet(first){
  if (first) {
    log('Try: "open YouTube", "call Amma", "remind me in 20 minutes to study", "message Tharun I\'m on the way", "quiz me on cell biology", "what\'s this" (camera), "any BOF signals", or just ask anything.', 'sys', false);
    speak(L('hello',"Echo online. Good to meet you on your phone, Paul."));
  } else {
    setState(S.SLEEPING);
    resumeWake();
  }
}

/* ── boot ── */
(function localize(){
  const w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  let n; while ((n = w.nextNode())) { const t = T(n.nodeValue); if (t !== n.nodeValue) n.nodeValue = t; }
  document.querySelectorAll('[placeholder],[aria-label]').forEach(el => {
    if (el.placeholder) el.placeholder = T(el.placeholder);
    if (el.getAttribute('aria-label')) el.setAttribute('aria-label', T(el.getAttribute('aria-label')));
  });
})();
$('greet').textContent = greeting();
loadChat();
syncUi();
renderModes();
setState(S.SLEEPING);
A.setLang(opt.lang==='ta' ? 'ta-IN' : 'en-IN');
A.setPitch(opt.pitch);
renderPower();
if (!A.getPref('apikey')) {
  $('setup').classList.add('show');
} else {
  if (!saved.length) log(L('ready','Ready. Tap the orb, or type below.'), 'sys', false);
  greet(false);
}
