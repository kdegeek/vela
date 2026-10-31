// Executes the shipped companion script against an in-memory DOM and HTTP boundary.
// No server, credentials, browser dependency, or Android process is needed.
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const html = fs.readFileSync(path.join(__dirname, '../../main/assets/phone.html'), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
const tick = async () => { for(let i=0;i<8;i++) await new Promise(resolve => setImmediate(resolve)); };
class Element {
 constructor(tag, attributes='') {
  this.tagName = tag.toUpperCase(); this.listeners = {}; this.children = []; this.textContent = ''; this.value = ''; this.disabled = false;
  this.dataset = {}; this.hidden = /\bhidden\b/.test(attributes); this.scrollHeight = 500; this.scrollTop = 400; this.clientHeight = 100;
  for(const [,name,value] of attributes.matchAll(/([\w-]+)="([^"]*)"/g)) {
   if(name === 'data-key') this.dataset.key = value; else this[name === 'class' ? 'className' : name] = value;
  }
 }
 addEventListener(type, listener) { this.listeners[type] = listener; }
 emit(type) { return this.listeners[type]?.({preventDefault(){}}); }
 replaceChildren(...children) { this.children = children; }
 append(...children) { this.children.push(...children); }
}
function state(overrides={}) {
 return {running:false,lines:[],tasks:[],pendingId:null,pending:null,settings:{model:'saved-model'},
  device:{model:'Emulator',androidApi:36,surfaceWidth:1920,surfaceHeight:1080,accessibility:false,mediaAccess:false},...overrides};
}
function setup({storageUnavailable=false,confirmAnswer=true}={}) {
 const elements = [...html.matchAll(/<([a-z][a-z0-9]*)\b([^>]*)>/g)].map(([,tag,attrs])=>new Element(tag,attrs));
 const ids = Object.fromEntries(elements.filter(e=>e.id).map(e=>[e.id,e]));
 for(const [,id,body] of html.matchAll(/<form id="([^"]+)"[^>]*>([\s\S]*?)<\/form>/g)) {
  ids[id].elements = [...body.matchAll(/<(input|select|textarea|button)\b([^>]*)>/g)].map(([,tag,attrs])=>{
   const id = attrs.match(/id="([^"]+)"/)?.[1], name = attrs.match(/name="([^"]+)"/)?.[1];
   return id ? ids[id] : elements.find(e=>e.tagName===tag.toUpperCase() && name && e.name===name) || new Element(tag,attrs);
  });
  ids[id].elements.namedItem = name => ids[id].elements.find(e=>e.name===name);
 }
 const controls = elements.filter(e=>['BUTTON','INPUT','SELECT','TEXTAREA'].includes(e.tagName) && e.id !== 'reconnect');
 const remote = elements.filter(e=>e.dataset.key);
 const requests = [], timers = [], historyCalls = [];
 let nextState = state(), fail = null, respond = null;
 const context = vm.createContext({
  document:{getElementById:id=>{assert.ok(ids[id],`Missing DOM element ${id}`);return ids[id];},querySelectorAll:selector=>selector==='[data-key]'?remote:controls,createElement:tag=>new Element(tag)},
  location:{hash:'#t=test-token',pathname:'/'},localStorage:{setItem(){if(storageUnavailable)throw Error('Storage unavailable');},getItem(){return 'test-token';}},history:{replaceState(...args){historyCalls.push(args);}},
  URLSearchParams,AbortSignal,JSON,Date,Set,Error,URL,Blob,crypto:require('node:crypto').webcrypto,confirm:()=>confirmAnswer,
  setTimeout:callback=>{timers.push(callback);return timers.length;},clearTimeout(){},
  fetch:async(url,opts)=>{
   requests.push({url,...opts});
   if(respond) { const result=await respond(url,opts); if(result) return result; }
   if(fail) throw Error(fail);
   return {ok:true,status:200,json:async()=>url==='/api/state'?nextState:{ok:true},text:async()=>''};
  }
 });
 vm.runInContext(script,context);
 return {ids,controls,remote,requests,historyCalls,context,model:ids.settingsForm.elements.namedItem('model'),setState:s=>nextState=s,setFailure:e=>fail=e,setResponder:r=>respond=r,
  poll:async()=>{await vm.runInContext('poll()',context);await tick();}};
}
test('offline actions start disabled, then enable only after authenticated state',async()=>{
 const ui=setup();assert.ok(ui.remote.every(e=>e.disabled));await tick();assert.ok(ui.remote.every(e=>!e.disabled));
 assert.equal(ui.model.value,'saved-model');assert.ok(ui.requests.every(r=>r.headers['X-Vela-Token']==='test-token'));
});
test('disconnect disables controls, hides stale approval, and preserves an unsent request',async()=>{
 const ui=setup();await tick();ui.setState(state({pendingId:'old',pending:'Open app?'}));await ui.poll();
 ui.ids.ask.value='Keep my draft';ui.setFailure('Network unavailable');await ui.poll();
 assert.ok(ui.controls.every(e=>e.disabled));assert.equal(ui.ids.pending.hidden,true);assert.equal(ui.ids.ask.value,'Keep my draft');
 assert.match(ui.ids.connection.textContent,/Disconnected/);assert.equal(ui.ids.connectionHelp.hidden,false);
 ui.setFailure(null);await ui.poll();assert.ok(ui.remote.every(e=>!e.disabled));
});
test('polling never overwrites unsaved connection edits',async()=>{
 const ui=setup();await tick();ui.model.value='my-unsaved-model';ui.setState(state({settings:{model:'another-model'}}));await ui.poll();
 assert.equal(ui.model.value,'my-unsaved-model');
});
test('agent ownership disables remote and immediately exposes Stop',async()=>{
 const ui=setup();await tick();ui.setState(state({running:true}));await ui.poll();
 assert.ok(ui.remote.every(e=>e.disabled));assert.equal(ui.ids.stop.hidden,false);assert.match(ui.ids.remoteStatus.textContent,/Stop the task/);
 await ui.remote[0].emit('click');assert.equal(ui.requests.filter(r=>r.url==='/api/remote').length,0);
});
test('approval sends the displayed ID once and stays locked through intervening polls',async()=>{
 const ui=setup();await tick();ui.setState(state({pendingId:'prompt-1',pending:'Open app?'}));await ui.poll();
 let release;ui.setResponder(url=>url==='/api/answer'?new Promise(resolve=>{release=resolve;}):null);
 const answering=ui.ids.allow.emit('click');await tick();
 ui.setState(state({pendingId:'prompt-2',pending:'A new question'}));await ui.poll();
 assert.equal(ui.ids.allow.disabled,true);await ui.ids.deny.emit('click');
 const requests=ui.requests.filter(r=>r.url==='/api/answer');assert.equal(requests.length,1);
 assert.deepEqual(JSON.parse(requests[0].body),{allow:true,promptId:'prompt-1'});
 release({ok:true,status:200,json:async()=>({ok:true})});await answering;
 assert.equal(ui.ids.pendingText.textContent,'A new question');assert.equal(ui.ids.allow.disabled,false);
});
test('a settings save cannot be submitted twice and rehydrates the saved masked values',async()=>{
 const ui=setup();await tick();ui.model.value='new-model';let release;
 ui.setResponder(url=>url==='/api/settings'?new Promise(resolve=>{release=resolve;}):null);
 ui.ids.settingsForm.emit('submit');await tick();assert.equal(ui.model.disabled,true);
 ui.ids.settingsForm.emit('submit');await tick();assert.equal(ui.requests.filter(r=>r.url==='/api/settings').length,1);
 ui.setState(state({settings:{model:'new-model'}}));release({ok:true,status:200,json:async()=>({ok:true})});await tick();
 assert.equal(ui.model.value,'new-model');assert.equal(ui.model.disabled,false);assert.match(ui.ids.saved.textContent,/Saved/);
});
test('failed Ask retains the draft and re-enables submission',async()=>{
 const ui=setup();await tick();ui.ids.ask.value='A long request';
 ui.setResponder(url=>url==='/api/ask'?{ok:false,status:503,text:async()=>'Model is unavailable'}:null);
 ui.ids.askForm.emit('submit');await tick();assert.equal(ui.ids.ask.value,'A long request');assert.equal(ui.ids.ask.disabled,false);
 assert.match(ui.ids.chatError.textContent,/Model is unavailable/);
});
test('malformed layout stays editable with useful feedback and no write request',async()=>{
 const ui=setup();await tick();ui.ids.layout.value='{bad';await ui.ids.saveLayout.emit('click');await tick();
 assert.match(ui.ids.layoutStatus.textContent,/not valid JSON/);assert.equal(ui.ids.layout.disabled,false);
 assert.equal(ui.requests.filter(r=>r.url==='/api/layout').length,0);
});
test('new conversation text is rendered as text and does not pull a reader to the bottom',async()=>{
 const ui=setup();await tick();ui.ids.log.scrollTop=0;
 ui.setState(state({lines:[{kind:'said',text:'<img src=x onerror=alert(1)>'}]}));await ui.poll();
 assert.equal(ui.ids.log.children[0].textContent,'<img src=x onerror=alert(1)>');assert.equal(ui.ids.log.scrollTop,0);
});
test('expired pairing retains recovery instructions and disables remote',async()=>{
 const ui=setup();await tick();ui.setResponder(url=>url==='/api/state'?{ok:false,status:401}:null);await ui.poll();
 assert.equal(ui.ids.unpaired.hidden,false);assert.ok(ui.remote.every(e=>e.disabled));assert.match(ui.ids.connection.textContent,/Scan the QR/);
});

test('pairing fragment is removed even when private browsing blocks localStorage',async()=>{
 const ui=setup({storageUnavailable:true});await tick();
 assert.deepEqual(ui.historyCalls,[[null,'','/']]);
 assert.equal(ui.requests[0].headers['X-Vela-Token'],'test-token');
});
test('remote requests cannot overlap or be unlocked by a background poll',async()=>{
 const ui=setup();await tick();let release;
 ui.setResponder(url=>url==='/api/remote'?new Promise(resolve=>{release=resolve;}):null);
 const sending=ui.remote[0].emit('click');await tick();await ui.poll();
 assert.ok(ui.remote.every(e=>e.disabled));await ui.remote[1].emit('click');
 assert.equal(ui.requests.filter(r=>r.url==='/api/remote').length,1);
 release({ok:true,status:200,json:async()=>({ok:true})});await sending;
 assert.ok(ui.remote.every(e=>!e.disabled));
});

const profile = (id='adult',extra={}) => ({id,displayName:'Morgan',kind:'adult',preferences:{audioLanguages:['en'],subtitleLanguages:['es'],subtitles:'automatic',autoplayNext:false,preferredMediaProviders:['jellyfin'],preferredLibraries:[]},restrictions:{allowHomeControl:false},...extra});
const ok = value => ({ok:true,status:200,json:async()=>value,text:async()=>''});
const refusal = (status,message) => ({ok:false,status,text:async()=>JSON.stringify({message})});
const buttonIn = (ui,id,label) => ui.ids[id].children.flatMap(article=>article.children).find(node=>node.tagName==='BUTTON' && node.textContent===label);
function management(ui,{profiles={revision:7,activeProfileId:'adult',profiles:[profile()]},memories={profileId:'adult',revision:7,total:0,entries:[]},routines={routines:[],history:[]},intercept=()=>null}={}) {
 ui.setResponder((url,opts)=>intercept(url,opts) || (url==='/api/profiles'?ok(profiles):url.startsWith('/api/profiles/')&&url.includes('/memories?')?ok(memories):url==='/api/routines'&&opts.method!=='POST'?ok(routines):url==='/api/external-control'?ok({enabled:false,address:'127.0.0.1:8766',credentialPresent:false}):null));
}
test('management data and private memory are loaded only by explicit actions, never by polling',async()=>{
 const ui=setup();await tick();await ui.poll();await ui.poll();
 assert.ok(ui.requests.every(r=>r.url==='/api/state'));management(ui);
 await ui.ids.loadProfiles.emit('click');assert.equal(ui.ids.memoryProfile.value,'adult');
 assert.equal(ui.requests.filter(r=>r.url.includes('/memories')).length,0);
 await ui.ids.loadMemories.emit('click');assert.equal(ui.requests.at(-1).url,'/api/profiles/adult/memories?offset=0&limit=20');
});
test('profile create and edit send full typed drafts and preserve existing restrictions and providers',async()=>{
 const ui=setup();await tick();management(ui);await ui.ids.loadProfiles.emit('click');
 buttonIn(ui,'profileList','Edit preferences').emit('click');ui.ids.profileName.value='New name';ui.ids.audioLanguages.value='en, fr';ui.ids.profileAutoplay.checked=true;
 ui.ids.profileForm.emit('submit');await tick();
 const request=ui.requests.find(r=>r.url==='/api/profiles/adult'&&r.method==='PUT');const body=JSON.parse(request.body);
 assert.equal(body.revision,7);assert.equal(body.profile.kind,'adult');assert.deepEqual(body.profile.restrictions,{allowHomeControl:false});
 assert.deepEqual(body.profile.preferences.preferredMediaProviders,['jellyfin']);assert.deepEqual(body.profile.preferences.audioLanguages,['en','fr']);assert.equal(body.profile.preferences.autoplayNext,true);
 ui.ids.newProfile.emit('click');ui.ids.profileName.value='Alex';ui.ids.profileForm.emit('submit');await tick();
 const created=JSON.parse(ui.requests.find(r=>r.url==='/api/profiles'&&r.method==='POST').body);
 assert.equal(created.profile.kind,'adult');assert.equal(created.profile.preferences.autoplayNext,false);assert.equal(created.profile.displayName,'Alex');
});
test('stale profile edit retains exact draft and revision across poll and explicit reload',async()=>{
 const ui=setup();await tick();management(ui,{intercept:(url,opts)=>opts.method==='PUT'?refusal(409,'Revision changed'):null});await ui.ids.loadProfiles.emit('click');
 buttonIn(ui,'profileList','Edit preferences').emit('click');ui.ids.profileName.value='Keep my edit';ui.ids.profileForm.emit('submit');await tick();
 assert.match(ui.ids.profileStatus.textContent,/Conflict or busy/);assert.equal(ui.ids.profileForm.hidden,false);assert.equal(ui.ids.profileName.value,'Keep my edit');
 management(ui,{profiles:{revision:8,activeProfileId:'adult',profiles:[profile()]},intercept:(url,opts)=>opts.method==='PUT'?refusal(409,'Revision changed'):null});
 await ui.poll();await ui.ids.loadProfiles.emit('click');ui.ids.profileForm.emit('submit');await tick();
 const writes=ui.requests.filter(r=>r.method==='PUT');assert.deepEqual(writes.map(r=>JSON.parse(r.body).revision),[7,7]);
 assert.equal(ui.ids.profileName.value,'Keep my edit');
});
test('switch/delete are deliberate revision-bound actions with active-profile deletion absent',async()=>{
 const ui=setup({confirmAnswer:false});await tick();management(ui,{profiles:{revision:7,activeProfileId:'adult',profiles:[profile(),profile('other',{displayName:'Alex'})]}});await ui.ids.loadProfiles.emit('click');
 assert.equal(ui.ids.profileList.children[0].children.filter(x=>x.textContent==='Delete profile…').length,0);
 await buttonIn(ui,'profileList','Switch to Alex').emit('click');await buttonIn(ui,'profileList','Delete profile…').emit('click');
 assert.equal(ui.requests.filter(r=>r.method==='POST'||r.method==='DELETE').length,0);
});
test('memory Save is explicit and profile-scoped, typed, and never accepts client provenance',async()=>{
 const ui=setup();await tick();management(ui);await ui.ids.loadProfiles.emit('click');await ui.ids.loadMemories.emit('click');ui.ids.newMemory.emit('click');
 ui.ids.memoryText.value='  I prefer captions  ';ui.ids.memoryTags.value='captions, films';ui.ids.memoryShare.checked=true;await ui.poll();
 assert.equal(ui.requests.filter(r=>r.method==='POST').length,0);
 ui.ids.memoryForm.emit('submit');await tick();const request=ui.requests.find(r=>r.method==='POST');
 assert.equal(request.url,'/api/profiles/adult/memories');assert.deepEqual(JSON.parse(request.body),{revision:7,kind:'preference',text:'I prefer captions',tags:['captions','films'],shareWithAgent:true});
});
test('memory pages use captured revisions; untrusted saved text is rendered as text',async()=>{
 const entry={id:'m1',kind:'fact',text:'<script>steal()</script>',tags:[],shareWithAgent:false};
 const ui=setup();await tick();management(ui,{memories:{profileId:'adult',revision:9,total:21,entries:[entry]}});await ui.ids.loadProfiles.emit('click');await ui.ids.loadMemories.emit('click');
 assert.equal(ui.ids.memoryList.children[0].children[1].textContent,entry.text);await ui.ids.moreMemories.emit('click');
 assert.match(ui.requests.at(-1).url,/offset=1&limit=20&expectedRevision=9$/);
});
test('memory conflicts retain the exact unsaved words and do not replay an edit',async()=>{
 const ui=setup();await tick();management(ui,{memories:{profileId:'adult',revision:7,total:1,entries:[{id:'m',kind:'fact',text:'Original',tags:[],shareWithAgent:false}]},intercept:(url,opts)=>opts.method==='PUT'?refusal(409,'Revision changed'):null});
 await ui.ids.loadProfiles.emit('click');await ui.ids.loadMemories.emit('click');buttonIn(ui,'memoryList','Edit memory').emit('click');ui.ids.memoryText.value='Keep exact words';ui.ids.memoryForm.emit('submit');await tick();await ui.poll();
 assert.equal(ui.ids.memoryForm.hidden,false);assert.equal(ui.ids.memoryText.value,'Keep exact words');assert.match(ui.ids.memoryStatus.textContent,/Conflict or busy/);
 assert.equal(ui.requests.filter(r=>r.method==='PUT').length,1);
});
test('memory clear, deletion and export require confirmation and do not run on load',async()=>{
 const ui=setup({confirmAnswer:false});await tick();management(ui,{memories:{profileId:'adult',revision:7,total:1,entries:[{id:'m',kind:'fact',text:'Private',tags:[],shareWithAgent:false}]}});
 await ui.ids.loadProfiles.emit('click');await ui.ids.loadMemories.emit('click');await buttonIn(ui,'memoryList','Delete memory…').emit('click');await ui.ids.clearMemories.emit('click');await ui.ids.exportProfile.emit('click');
 assert.equal(ui.requests.filter(r=>r.method==='DELETE'||r.method==='POST'||r.url.endsWith('/export')).length,0);
});
test('private export is only fetched deliberately and can be discarded',async()=>{
 const ui=setup();await tick();management(ui,{intercept:url=>url.endsWith('/export')?ok({privateText:'owner only'}):null});await ui.ids.loadProfiles.emit('click');await ui.ids.exportProfile.emit('click');
 assert.equal(ui.ids.exportPanel.hidden,false);assert.match(ui.ids.profileExport.value,/owner only/);ui.ids.closeExport.emit('click');assert.equal(ui.ids.profileExport.value,'');assert.equal(ui.ids.exportPanel.hidden,true);
});
test('all schedule forms send exact RoutineSpec body types and retain named calendar zones',async()=>{
 const ui=setup();await tick();management(ui);ui.ids.newRoutine.emit('click');ui.ids.routineName.value='Evening';ui.ids.routineTask.value='Dim the lights';
 for(const kind of ['once','interval','weekly','monthly']) {
  ui.ids.routineSchedule.value=kind;ui.ids.routineSchedule.emit('change');ui.ids.routineInstant.value='2026-11-01T18:30:00-06:00';ui.ids.routineInterval.value='15';ui.ids.routineZone.value='America/Chicago';ui.ids.routineTime.value='18:30';ui.ids.routineDays.value='1,3,5';ui.ids.routineDay.value='31';
  ui.ids.routineForm.emit('submit');await tick();
  const spec=JSON.parse(ui.requests.filter(r=>r.url==='/api/routines'&&r.method==='POST').at(-1).body).spec;
  assert.equal(spec.schedule.type,kind);assert.equal(spec.enabled,true);assert.equal(spec.maxLatenessMillis,86400000);
  if(kind==='weekly'||kind==='monthly') assert.equal(spec.schedule.zone,'America/Chicago');
  if(kind==='weekly') assert.deepEqual(spec.schedule.days,[1,3,5]);
  if(kind==='monthly') assert.equal(spec.schedule.day,31);
  if(kind==='interval') assert.equal(spec.schedule.everyMillis,900000);
 }
});
test('one-shot requires an explicit offset instead of silently applying the phone timezone',async()=>{
 const ui=setup();await tick();management(ui);ui.ids.newRoutine.emit('click');ui.ids.routineInstant.value='2026-11-01T18:30';ui.ids.routineForm.emit('submit');await tick();
 assert.match(ui.ids.routineStatus.textContent,/UTC offset/);assert.equal(ui.requests.filter(r=>r.method==='POST').length,0);assert.equal(ui.ids.routineForm.hidden,false);
});
const routine = () => ({id:'r',revision:3,nextRunAt:null,spec:{name:'Evening',task:'A task',schedule:{type:'weekly',days:[1],time:'18:30',zone:'America/Chicago'},enabled:true,missedRunPolicy:'RUN_LATEST',maxLatenessMillis:86400000}});
test('unknown manual-run result reuses the same key; successful deliberate next run gets a new key',async()=>{
 const ui=setup();await tick();let lost=true;
 management(ui,{routines:{routines:[routine()],history:[]},intercept:url=>url==='/api/routines/r/run'?(lost?Promise.reject(Error('Lost response')):ok({id:'run',state:'QUEUED'})):null});
 await ui.ids.loadRoutines.emit('click');await buttonIn(ui,'routineList','Run now').emit('click');assert.match(ui.ids.routineStatus.textContent,/unknown/);
 await ui.ids.loadRoutines.emit('click');lost=false;await buttonIn(ui,'routineList','Retry same run request').emit('click');await buttonIn(ui,'routineList','Run now').emit('click');
 const keys=ui.requests.filter(r=>r.url==='/api/routines/r/run').map(r=>JSON.parse(r.body).requestKey);
 assert.equal(keys.length,3);assert.equal(keys[0],keys[1]);assert.notEqual(keys[1],keys[2]);assert.match(ui.ids.routineStatus.textContent,/QUEUED/);
});
test('routine management serializes concurrent requests across polling and uses definition revision',async()=>{
 const ui=setup();await tick();let release;
 management(ui,{routines:{routines:[routine()],history:[]},intercept:url=>url.endsWith('/enabled')?new Promise(resolve=>{release=resolve;}):null});await ui.ids.loadRoutines.emit('click');
 const disabling=buttonIn(ui,'routineList','Disable').emit('click');await tick();await ui.poll();assert.equal(ui.ids.newRoutine.disabled,true);await buttonIn(ui,'routineList','Disable').emit('click');
 const writes=ui.requests.filter(r=>r.url.endsWith('/enabled'));assert.equal(writes.length,1);assert.deepEqual(JSON.parse(writes[0].body),{revision:3,enabled:false});release(ok({}));await disabling;
});
test('run history exposes actual states, cancel and deliberate restart endpoints',async()=>{
 const ui=setup();await tick();management(ui,{routines:{routines:[routine()],history:[{id:'run',routineId:'r',name:'Old name',state:'RUNNING',queuedAt:1,startedAt:2,finishedAt:null,detail:'Awaiting approval'}]}});await ui.ids.loadRoutines.emit('click');
 assert.match(ui.ids.routineHistory.children[0].children[0].textContent,/RUNNING/);await buttonIn(ui,'routineHistory','Cancel run').emit('click');assert.deepEqual(JSON.parse(ui.requests.find(r=>r.url.endsWith('/cancel')).body),{});
 await buttonIn(ui,'routineHistory','Restart using current definition…').emit('click');const body=JSON.parse(ui.requests.find(r=>r.url.endsWith('/restart')).body);assert.equal(typeof body.requestKey,'string');
});
test('external credential comes only from enable, stays out of polling and is discarded by revoke',async()=>{
 const ui=setup();await tick();management(ui,{intercept:url=>url.endsWith('/enable')?ok({status:{enabled:true},token:'dedicated-secret'}):null});await ui.ids.loadExternal.emit('click');assert.equal(ui.ids.externalCredential.value,'');
 await ui.ids.enableExternal.emit('click');assert.equal(ui.ids.externalCredential.value,'dedicated-secret');assert.equal(ui.ids.externalCredentialPanel.hidden,false);assert.equal(ui.ids.externalCredential.type,'password');
 await ui.poll();await ui.ids.loadExternal.emit('click');assert.equal(ui.ids.externalCredential.value,'dedicated-secret');
 assert.deepEqual(JSON.parse(ui.requests.find(r=>r.url.endsWith('/enable')).body),{ownerConfirmed:true});assert.ok(!ui.requests.some(r=>r.body?.includes('dedicated-secret')));
 await ui.ids.revokeExternal.emit('click');assert.equal(ui.ids.externalCredential.value,'');assert.equal(ui.ids.externalCredentialPanel.hidden,true);assert.match(ui.ids.externalStatus.textContent,/drained/);
});
test('unsafe household revisions are rejected before creating an editable profile draft',async()=>{
 const ui=setup();await tick();management(ui,{profiles:{revision:9007199254740992,activeProfileId:'adult',profiles:[profile()]}});await ui.ids.loadProfiles.emit('click');
 assert.match(ui.ids.profileStatus.textContent,/cannot be safely edited/);assert.equal(ui.ids.newProfile.disabled,true);assert.equal(ui.requests.filter(r=>r.method==='POST').length,0);
});
test('household recovery is explicit, stops through the facade route and does not reset drafts',async()=>{
 const ui=setup();await tick();management(ui);await ui.ids.loadProfiles.emit('click');buttonIn(ui,'profileList','Edit preferences').emit('click');ui.ids.profileName.value='Retained draft';await ui.ids.recoverHousehold.emit('click');
 const request=ui.requests.find(r=>r.url==='/api/household/reload');assert.equal(request.method,'POST');assert.deepEqual(JSON.parse(request.body),{});assert.equal(ui.ids.profileName.value,'Retained draft');assert.equal(ui.ids.profileForm.hidden,false);
});
test('malformed interval and weekday numeric inputs never serialize NaN or fractional revisions',async()=>{
 const ui=setup();await tick();management(ui);ui.ids.newRoutine.emit('click');ui.ids.routineSchedule.value='interval';ui.ids.routineInstant.value='2026-11-01T18:30:00Z';ui.ids.routineInterval.value='not-a-number';ui.ids.routineForm.emit('submit');await tick();
 assert.match(ui.ids.routineStatus.textContent,/Interval must/);assert.equal(ui.requests.filter(r=>r.method==='POST').length,0);
 ui.ids.routineSchedule.value='weekly';ui.ids.routineDays.value='1,8';ui.ids.routineForm.emit('submit');await tick();assert.match(ui.ids.routineStatus.textContent,/weekdays/);assert.equal(ui.requests.filter(r=>r.method==='POST').length,0);
});
test('disconnect discards a displayed external credential and gates dynamic management buttons',async()=>{
 const ui=setup();await tick();management(ui,{intercept:url=>url.endsWith('/enable')?ok({status:{enabled:true},token:'private-token'}):null});await ui.ids.loadProfiles.emit('click');await ui.ids.enableExternal.emit('click');
 ui.setResponder(null);ui.setFailure('Offline');await ui.poll();assert.equal(ui.ids.externalCredential.value,'');assert.equal(ui.ids.externalCredentialPanel.hidden,true);assert.equal(buttonIn(ui,'profileList','Edit preferences').disabled,true);
});
test('existing external credentials require revocation before another enable',async()=>{
 const ui=setup();await tick();
 let present=true;
 management(ui,{intercept:(url)=>url==='/api/external-control'?ok({enabled:present,credentialPresent:present}):url.endsWith('/revoke')?(present=false,ok({})):null});
 await ui.ids.loadExternal.emit('click');assert.equal(ui.ids.enableExternal.disabled,true);
 await ui.poll();assert.equal(ui.ids.enableExternal.disabled,true);
 await ui.ids.enableExternal.emit('click');assert.ok(!ui.requests.some(r=>r.url.endsWith('/enable')));
 await ui.ids.revokeExternal.emit('click');assert.equal(ui.ids.enableExternal.disabled,false);
});

test('Plex fields are optional labeled address and private token controls',()=>{
 assert.match(html,/<label>Plex server address<input name="plexUrl" type="url"[^>]*maxlength="2048"/);
 assert.match(html,/<label>Plex server token<input name="plexToken" type="password" autocomplete="off" maxlength="8192"/);
 assert.match(html,/Search does not start or verify playback/);
 assert.doesNotMatch(html,/<input[^>]*name="plexToken"[^>]*\brequired\b/);
});
test('Plex token stays masked across polls and phone submits masked merge with the current server',async()=>{
 const ui=setup();await tick();
 // Simulate a fresh pairing/state hydration, including every pre-existing connection and appearance field.
 vm.runInContext('loaded=false',ui.context);
 ui.setState(state({settings:{plexUrl:'http://plex.local:32400',plexToken:'••••••',model:'saved-model',homeAssistantToken:'••••••',jellyfinToken:'••••••',reduceMotion:true}}));await ui.poll();
 const field = name=>ui.ids.settingsForm.elements.namedItem(name);
 assert.equal(field('plexToken').value,'••••••');assert.equal(field('plexToken').type,'password');
 field('plexUrl').value='http://new-plex.local:32400';
 ui.setState(state({settings:{plexUrl:'http://plex.local:32400',plexToken:'••••••'}}));await ui.poll();
 assert.equal(field('plexUrl').value,'http://new-plex.local:32400');
 await ui.ids.settingsForm.emit('submit');await tick();
 const save=ui.requests.find(r=>r.url==='/api/settings');assert.ok(save);
 const body=JSON.parse(save.body);
 assert.equal(body.plexUrl,'http://new-plex.local:32400');assert.equal(body.plexToken,'••••••');
 assert.equal(body.homeAssistantToken,'••••••');assert.equal(body.jellyfinToken,'••••••');assert.equal(body.reduceMotion,true);
 assert.equal(save.headers['X-Vela-Token'],'test-token');
});
test('failed Plex settings save preserves edits and does not clear other connection credentials',async()=>{
 const ui=setup();await tick();const field=name=>ui.ids.settingsForm.elements.namedItem(name);
 field('plexUrl').value='http://plex.local:32400';field('plexToken').value='new-owner-token';field('homeAssistantToken').value='••••••';
 ui.setResponder(url=>url==='/api/settings'?refusal(400,'Invalid Plex credential.'):null);
 await ui.ids.settingsForm.emit('submit');await tick();
 assert.equal(field('plexToken').value,'new-owner-token');assert.equal(field('homeAssistantToken').value,'••••••');
 assert.equal(field('plexToken').disabled,false);assert.match(ui.ids.saved.textContent,/Invalid Plex credential/);
 assert.ok(!ui.ids.saved.textContent.includes('new-owner-token'));
});
