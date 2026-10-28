'use strict';
const $ = id => document.getElementById(id);
const token = document.querySelector('meta[name="control-token"]').content;
let currentRun = '', lastSignature = '', offline = false;
const text = (tag, value, className) => { const node = document.createElement(tag); node.textContent = value; if (className) node.className = className; return node; };
const kind = envelope => envelope.event.payload.event_type;
const short = value => value ? String(value).replace(/@[a-f0-9-]+/g, '').replace(/^([a-f0-9]{8})[a-f0-9-]{28}$/, '$1…') : '—';
const relative = (time, start) => `${Math.max(0, (time - start) / 1000).toFixed(2)}s`;
function render(state) {
  const openDetails = new Set([...document.querySelectorAll('details[data-key][open]')].map(node => node.dataset.key));
  const manifest = state.manifest;
  const run = manifest?.run_id || '';
  if (currentRun !== run) { currentRun = run; lastSignature = ''; }
  $('source').textContent = state.data_source;
  $('run-id').textContent = run || 'No active run';
  $('records').textContent = state.observed_total;
  $('phase').textContent = state.phase === 'STOPPED' ? 'STOPPED' : state.phase;
  $('confirmed').textContent = state.first_deadlock ? 'YES · CONFIRMED' : '—';
  $('start').disabled = state.phase === 'RUNNING';
  $('scenario').disabled = state.phase === 'RUNNING';
  $('control-note').textContent = state.error || (state.phase === 'RUNNING' ? 'Closed ring stays blocked until you kill its workload PID. Control scenarios finish normally.' : 'One run at a time. Every request and verdict is computed live.');
  if (!manifest) return;
  const serviceByInstance = Object.fromEntries(manifest.instances.map(instance => [instance.instance_id, instance.service_binding]));
  const observations = state.observations;
  const arrivals = new Map(), lastByInvocation = new Map(), byInstance = new Map();
  for (const envelope of observations) {
    const e = envelope.event, p = e.payload;
    if (!byInstance.has(e.instance_id)) byInstance.set(e.instance_id, []);
    byInstance.get(e.instance_id).push(e);
    if (p.event_type === 'rpc.invocation.arrived') arrivals.set(p.invocation_id, envelope);
    if (p.invocation_id && !['rpc.invocation.sent','execution.lease','rpc.block.begin','rpc.block.end'].includes(p.event_type)) {
      const previous = lastByInvocation.get(p.invocation_id);
      if (!previous || previous.instance_id !== e.instance_id || previous.local_seq < e.local_seq) lastByInvocation.set(p.invocation_id, e);
    }
  }
  $('arrivals').textContent = arrivals.size;
  $('call-count').textContent = `${arrivals.size} received call${arrivals.size === 1 ? '' : 's'}`;
  const services = $('services'); services.replaceChildren();
  for (const pool of manifest.pools) {
    const events = (byInstance.get(pool.instance_id) || []).sort((a,b) => a.local_seq - b.local_seq);
    const owners = new Map(pool.unit_ids.map(unit => [unit, 'unknown'])), blocks = new Set(), queued = new Set();
    let lastSeq = 0;
    for (const e of events) {
      const p = e.payload; lastSeq = Math.max(lastSeq, e.local_seq);
      if (p.event_type === 'resource.init' && p.resource_id === pool.resource_id) for (const id of p.unit_ids) owners.set(id, null);
      if (p.event_type === 'resource.acquire') { owners.set(p.resource_instance_id, p.execution_id); queued.delete(p.invocation_id); }
      if (p.event_type === 'resource.release') { owners.set(p.resource_instance_id, null); blocks.delete(p.execution_id); }
      if (p.event_type === 'resource.wait.begin') queued.add(p.invocation_id);
      if (p.event_type === 'execution.cancel' && !p.execution_id) queued.delete(p.invocation_id);
      if (p.event_type === 'rpc.block.begin') blocks.add(p.execution_id);
      if (p.event_type === 'rpc.block.end' || p.event_type === 'execution.end') blocks.delete(p.execution_id);
    }
    const exited = state.application_pid && !state.application_alive;
    const card = text('div', '', exited ? 'service stopped' : 'service'); card.append(text('h3', `${serviceByInstance[pool.instance_id]}${exited ? ' · STOPPED' : ''}`));
    card.append(text('p', `${pool.unit_ids.length} declared worker${pool.unit_ids.length === 1 ? '' : 's'} · seq ${lastSeq || '—'}`));
    card.append(text('p', state.discarded_observations ? 'State unknown · display history truncated' : `${exited ? 'Last telemetry: ' : ''}${blocks.size} blocked · ${queued.size} queued`));
    const slots = text('div', '', 'slots');
    for (const unit of pool.unit_ids) { const owner = owners.get(unit); const slot = text('span', '', `slot ${owner === 'unknown' ? 'unknown' : owner ? 'held' : ''}`); slot.title = `${exited ? 'Historical observation: ' : ''}${short(unit)}: ${owner === 'unknown' ? 'unknown' : owner ? short(owner) : 'observed free'}`; slots.append(slot); }
    card.append(slots); services.append(card);
  }
  const calls = $('calls'); calls.replaceChildren();
  const statusName = {'rpc.invocation.arrived':'arrived','resource.wait.begin':'queued','resource.acquire':'assigned','rpc.response.sent':'response sent','execution.cancel':'cancel requested','execution.end':'ended'};
  for (const [invocation, envelope] of [...arrivals].reverse()) {
    const e = envelope.event, last = lastByInvocation.get(invocation) || e;
    const status = statusName[last.payload.event_type] || last.payload.event_type;
    const row = document.createElement('tr'); row.append(text('td', relative(envelope.receipt_wall_ms, state.started_at_ms)), text('td', serviceByInstance[e.instance_id]), text('td', short(invocation), 'mono'));
    row.cells[2].title = invocation;
    const cell = document.createElement('td'); cell.append(text('span', status, `badge ${status}`)); row.append(cell); calls.append(row);
  }
  if (!arrivals.size) { const row = document.createElement('tr'), cell = text('td',state.discarded_observations ? 'No arrivals retained in the bounded display history.' : state.application_pid && !state.application_alive ? 'The application has stopped.' : 'Waiting for received request headers…','empty'); cell.colSpan = 4; row.append(cell); calls.append(row); }
  const signature = `${state.observed_total}:${run}`;
  if (signature !== lastSignature) {
    lastSignature = signature; const eventList = $('events'); eventList.replaceChildren();
    for (const envelope of observations.slice(-80).reverse()) {
      const row = text('div', '', 'event'); row.append(text('time', relative(envelope.receipt_wall_ms, state.started_at_ms)), text('span', serviceByInstance[envelope.event.instance_id] || 'driver'), text('span', `${kind(envelope)}  #${envelope.event.local_seq}`, 'event-name')); eventList.append(row);
    }
  }
  $('retention').textContent = state.discarded_observations ? `${state.discarded_observations} older display records omitted. This does not discard analyzer evidence.` : 'Display shows up to 2,000 received records; event stream shows the latest 80.';
  const report = state.latest_report, positive = state.first_deadlock;
  const displayReport = positive || report, panel = $('verdict-panel');
  const labels = {CONFIRMED_DEADLOCK:['DEADLOCK DETECTED','deadlock'],CONFIRMED_NO_DEADLOCK:['No deadlock at this cut','negative'],CANDIDATE_INCONCLUSIVE:['Not yet confirmed','inconclusive'],INSUFFICIENT_OBSERVABILITY:['Scope cannot be evaluated','inconclusive']};
  let info = displayReport ? labels[displayReport.verdict] : ['Waiting for analyzer','neutral'];
  if (state.phase === 'STOPPED' && !positive) info = ['Application stopped','neutral'];
  if (positive && !state.application_alive && state.exit_code != null) info = ['DEADLOCK ENDED · APPLICATION STOPPED','resolved'];
  panel.className = `panel verdict ${info[1]}`; $('verdict-title').textContent = info[0];
  $('verdict-detail').textContent = positive
    ? (state.application_alive ? 'CONFIRMED_DEADLOCK · Blocking RPC / application-worker capacity. Stop the workload PID to end this run.' : 'The workload process exited, ending this running deadlock. The historical confirmation remains recorded; requests were terminated, not successfully completed.')
    : state.phase === 'STOPPED' ? 'Process exit was observed. Exit alone does not certify a no-deadlock verdict.'
    : report ? report.verdict : 'The analyzer is starting and receiving telemetry.';
  $('missing').replaceChildren(); for (const item of report?.coverage.missing || []) { const badge = text('span', item.code); badge.title = item.subject; $('missing').append(badge); }
  const when = ms => new Date(ms).toLocaleTimeString();
  $('cut').textContent = displayReport?.query.certified_through_wall_ms ? `Reported certified horizon: ${when(displayReport.query.certified_through_wall_ms)}. Scope: ${displayReport.query.scope_instances.length} declared instances, including the driver.` : '';
  $('latest-cut').textContent = report ? `Latest analyzer cut: ${report.verdict} at ${when(report.evaluated_at_wall_ms)}. ${positive ? 'A later cut does not erase the earlier deadlock.' : ''}` : '';
  $('raw').textContent = JSON.stringify(report, null, 2);
  const result = $('result'); result.replaceChildren();
  const sample = state.diagnostics;
  const incidentSample = state.deadlock_diagnostics;
  const runtimeWorker = id => (incidentSample?.workers || []).find(w => w.execution_id === id) || (sample?.workers || []).find(w => w.execution_id === id);
  if (positive) {
    $('evidence-label').textContent = 'CONFIRMED_DEADLOCK';
    result.append(text('h3', state.application_alive ? 'Workers in the confirmed deadlock' : 'Workers in the confirmed historical deadlock'));
    result.append(text('p', `${positive.core.executions.length} blocked executions · ${positive.core.units.length} occupied application slots · ${positive.core.queue_entries.length} queued callbacks`, 'result-confirmed'));
    result.append(text('p', `${(positive.persistence.narrowed_span_ms/1000).toFixed(2)}s certified persistence ≥ τ ${(positive.persistence.tau_ms/1000).toFixed(2)}s. Interval ${when(positive.persistence.first_ms)} – ${when(positive.persistence.last_ms)}.`, 'muted'));
    const executionById = new Map(positive.core.executions.map(e => [e.id,e]));
    const queueById = new Map(positive.core.queue_entries.map(q => [q.invocation_id,q]));
    const serviceForExecution = id => {
      const unit = positive.core.units.find(u => u.owner_execution_id === id);
      const pool = manifest.pools.find(p => p.unit_ids.includes(unit?.id));
      return serviceByInstance[pool?.instance_id] || id;
    };
    const labelNode = id => executionById.has(id) ? `${serviceForExecution(id)} execution ${short(id)}`
      : queueById.has(id) ? `queued callback at ${serviceByInstance[queueById.get(id).instance_id]}`
      : short(id);
    for (const execution of positive.core.executions) {
      const units = positive.core.units.filter(unit => unit.owner_execution_id === execution.id);
      const edge = positive.core.edges.find(e => e.kind === 'wait' && e.from === execution.id);
      const worker = runtimeWorker(execution.id);
      const row = text('div', '', 'evidence-row');
      row.append(text('strong', `${serviceForExecution(execution.id)} · ${units.map(u => u.id.split(':').at(-1)).join(', ')} · RPC BLOCKED`));
      row.append(text('p', `Waits for ${edge ? labelNode(edge.to) : execution.blocked_on_invocation}. Retains its slot until actual task exit.`));
      if (worker) row.append(text('p', `Actual Java thread: ${worker.thread_name} · ID ${worker.java_thread_id} · sampled ${worker.thread_state}`, 'mono'));
      const details = document.createElement('details'); details.dataset.key = `execution:${execution.id}`; details.append(text('summary', 'Exact execution, slot and RPC identities'));
      details.append(text('pre', `Execution: ${execution.id}\nSlot: ${units.map(u => u.id).join(', ')}\nInbound invocation: ${execution.invocation_id}\nWaiting on invocation: ${execution.blocked_on_invocation}\nBlock event: ${execution.block_ref}`));
      row.append(details); result.append(row);
    }
    result.append(text('h3', 'Wait-for dependencies'));
    for (const edge of positive.core.edges.filter(e => e.kind === 'wait')) result.append(text('p', `${labelNode(edge.from)} → ${labelNode(edge.to)}`, 'dependency'));
    for (const queue of positive.core.queue_entries) {
      const pool = manifest.pools.find(p => p.instance_id === queue.instance_id && p.resource_id === queue.resource_id);
      const allHeld = pool && pool.unit_ids.every(id => positive.core.units.some(unit => unit.id === id));
      result.append(text('p', `Callback ${queue.invocation_id} queues at ${serviceByInstance[queue.instance_id]}, pool ${queue.resource_id}. ${allHeld ? 'Every eligible slot is owned inside this closed wait set.' : 'See exact eligible-slot evidence in the report.'}`, 'muted'));
    }
    const evidenceDetails = document.createElement('details'); evidenceDetails.dataset.key = `report:${positive.run_id}`; evidenceDetails.append(text('summary','Full confirmed deadlock report JSON'),text('pre',JSON.stringify(positive,null,2))); result.append(evidenceDetails);
    if (incidentSample) result.append(text('p',`Associated runtime sample: ${when(incidentSample.sampled_at_wall_ms)}. A diagnostic sample is not a certified simultaneous snapshot.`, 'caption'));
  } else {
    $('evidence-label').textContent = state.phase === 'COMPLETE' ? 'Run completed' : 'No confirmed deadlock';
    result.append(text('p', state.phase === 'COMPLETE' ? `Root outcome: ${state.root_outcome} · ${state.root_hops_completed} hops completed.` : state.phase === 'STOPPED' ? 'The workload exited before a deadlock was confirmed.' : 'Waiting for the analyzer to certify complete evidence.', state.phase === 'COMPLETE' && state.root_outcome === 'OK' ? 'result-good' : 'muted'));
  }
  if (state.artifacts) result.append(text('p',state.artifacts,'caption'));
  const process = $('process'); process.replaceChildren();
  $('process-status').textContent = state.application_pid ? state.application_alive ? 'RUNNING · separate workload JVM' : `EXITED · code ${state.exit_code ?? 'pending'}` : 'Starting workload…';
  if (state.application_pid) {
    process.append(text('p', `Workload PID: ${state.application_pid}`, 'pid'));
    process.append(text('p', 'svcA, svcB and svcC share this workload JVM. The monitor and analyzer run separately.', 'muted'));
    if (state.application_alive) {
      process.append(text('p', 'To stop all three services, run this in your terminal:', 'muted'), text('code', `kill -TERM ${state.application_pid}`, 'kill-command'));
      process.append(text('p', run.startsWith('closed-ring-') ? 'Interactive closed ring: no eight-second stop and no RPC deadline. The page observes PID exit automatically.' : 'Finite control: normal completion or the propagated 30-second RPC deadline ends the call.', 'caption'));
    } else process.append(text('p', 'Application stopped. Its workers are no longer running. Historical reports and sampled diagnostics remain available.', 'muted'));
  }
  const runtime = $('runtime'); runtime.replaceChildren();
  if (sample) {
    $('diagnostic-time').textContent = `${state.application_alive ? 'Sampled' : 'Last sample before exit'} ${when(sample.sampled_at_wall_ms)}`;
    const mb = bytes => bytes < 0 ? 'unavailable' : `${(bytes / 1048576).toFixed(1)} MiB`;
    const facts = [ ['PID',sample.pid], ['OS',`${sample.os_name} ${sample.os_version}`], ['Architecture',sample.architecture], ['Available processors',sample.processors], ['JVM',`${sample.vm_name} · Java ${sample.java_version}`], ['JVM heap used / committed / max',`${mb(sample.heap_used_bytes)} / ${mb(sample.heap_committed_bytes)} / ${mb(sample.heap_max_bytes)}`], ['Native OS thread IDs / kernel wait channels','Not collected; Java thread IDs are shown below'] ];
    const list = text('dl','','runtime-facts');
    for (const [key,value] of facts) list.append(text('dt',key),text('dd',value));
    runtime.append(list);
    for (const worker of sample.workers) {
      const details = document.createElement('details'); details.dataset.key = `thread:${worker.execution_id}`;
      details.append(text('summary',`${worker.service} · ${worker.unit_id.split(':').at(-1)} · ${worker.thread_name} (Java ID ${worker.java_thread_id}) · ${worker.thread_state}`));
      const cpu = worker.cpu_time_ns == null ? 'unavailable' : `${(worker.cpu_time_ns/1000000).toFixed(2)} ms`;
      details.append(text('pre',`Sample: ${new Date(sample.sampled_at_wall_ms).toISOString()}\nExecution: ${worker.execution_id}\nSlot: ${worker.unit_id}\nCPU time (cumulative): ${cpu}\nJVM monitor-blocked count: ${worker.blocked_count}\nJVM waited count: ${worker.waited_count}\nJVM wait/lock object: ${worker.lock_name || 'none reported'}\nJVM lock owner: ${worker.lock_owner_name || 'none reported'} (Java ID ${worker.lock_owner_java_thread_id ?? 'unavailable'})\n\nStack trace:\n${worker.stack.join('\n')}`));
      runtime.append(details);
    }
    if (!sample.workers.length) runtime.append(text('p','No active application handler was present in this diagnostic sample.','muted'));
  }
  for (const details of document.querySelectorAll('details[data-key]')) details.open = openDetails.has(details.dataset.key);
  $('activity').replaceChildren(); for (const message of state.progress) $('activity').append(text('li',message));
}
async function poll() {
  try { const response = await fetch('/api/state', {cache:'no-store'}); if (!response.ok) throw new Error('Monitor unavailable'); render(await response.json()); $('connection').textContent = '● Connected · local live feed'; offline = false; }
  catch (error) { $('connection').textContent = '○ Monitor disconnected'; offline = true; }
  setTimeout(poll, 250);
}
$('start').addEventListener('click',async () => {
  if (offline) return;
  $('start').disabled = true;
  try { const response = await fetch(`/api/run?scenario=${encodeURIComponent($('scenario').value)}`, {method:'POST',headers:{'X-SpanLease-Control':token}}); if (!response.ok) throw new Error(await response.text()); }
  catch(error) { $('control-note').textContent = error.message; $('start').disabled = false; }
});
poll();
