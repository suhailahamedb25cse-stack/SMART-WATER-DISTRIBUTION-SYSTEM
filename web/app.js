// The website's JavaScript. It only shows data and sends the user's actions
// to the Java server. All water calculations and all permission checks are
// done by the Java code.

let me = null;            // who is logged in {username, fullName, role}
let data = null;          // the latest data received from the server
let lastText = "";        // used to skip redrawing when nothing has changed
let editingReservoir = null;   // id of the reservoir being edited (null = adding a new one)
let editingArea = null;        // id of the area being edited (null = adding a new one)

// ---------- small helpers ----------

function el(id) { return document.getElementById(id); }

// litres with thousands separators, e.g. 18,000
function fmt(n) { return Math.round(n).toLocaleString('en-IN'); }

// makes user-typed text safe to put inside HTML
function esc(text) {
  return String(text).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
                     .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function barColor(percent) {
  if (percent > 50) return 'var(--green)';
  if (percent > 15) return 'var(--amber)';
  return 'var(--red)';
}

function toast(message, isError) {
  const box = el('toast');
  box.textContent = message;
  box.className = isError ? 'toast error' : 'toast';
  clearTimeout(toast.timer);
  toast.timer = setTimeout(function () { box.className = 'toast hidden'; }, 3200);
}

function setConnected(ok) {
  el('dot').className = ok ? 'dot' : 'dot off';
  el('dbText').textContent = ok ? 'database connected' : 'cannot reach the server - is the Java program running?';
}

function goToLogin() {
  window.location = '/login.html';
}

// ---------- talking to the server ----------

// sends a request and returns the JSON answer; throws an Error with the server's message if it fails.
// For POST the values are sent in the body of the request.
async function callApi(path, params, isPost) {
  let options = { method: isPost ? 'POST' : 'GET' };
  if (isPost) {
    options.headers = { 'Content-Type': 'application/x-www-form-urlencoded' };
    options.body = new URLSearchParams(params || {});
  }
  const response = await fetch(path, options);
  if (response.status === 401) {      // not logged in, or the session ended
    goToLogin();
    throw new Error('Please log in.');
  }
  const result = await response.json();
  if (!response.ok) {
    throw new Error(result.error || 'Request failed');
  }
  return result;
}

// reads everything from the server (also runs every 3 seconds)
async function load() {
  try {
    const response = await fetch('/api/status');
    if (response.status === 401) {
      goToLogin();
      return;
    }
    const text = await response.text();
    setConnected(true);
    if (text !== lastText) {          // only redraw when something changed
      lastText = text;
      data = JSON.parse(text);
      render();
      if (isTabOpen('history')) loadHistoryAll();
    }
  } catch (e) {
    setConnected(false);
  }
}

// changes something (add, update, delete ...) and shows the result
async function change(path, params, successMessage) {
  try {
    data = await callApi(path, params, true);
    lastText = '';                    // make sure the next refresh redraws
    render();
    if (isTabOpen('history')) loadHistoryAll();
    toast(successMessage, false);
    return true;
  } catch (e) {
    toast(e.message, true);
    return false;
  }
}

// ---------- drawing the pages ----------

function render() {
  drawDashboard();
  drawReservoirTable();
  drawAreaTable();
  drawPipelineTable();
  drawMaintenance();
  fillPipelineSelects();
  fillReadingSelects();
  drawDay();
  drawMap();
}

// the repair job (if any) of a pipeline: {job, text}
function jobFor(pipelineId) {
  const m = data.maintenance;
  if (m.current && m.current.pipelineId === pipelineId) {
    return 'Job #' + m.current.id + ' - being repaired';
  }
  for (let i = 0; i < m.pending.length; i++) {
    if (m.pending[i].pipelineId === pipelineId) {
      return 'Job #' + m.pending[i].id + ' - waiting (position ' + (i + 1) + ')';
    }
  }
  return '';
}

function drawDashboard() {
  el('tAvail').innerHTML = fmt(data.totalAvailable) + ' <span class="unit">L</span>';
  el('tAlloc').innerHTML = fmt(data.totalAllocated) + ' <span class="unit">L</span>';
  el('tRem').innerHTML = fmt(data.totalRemaining) + ' <span class="unit">L</span>';

  // alerts
  let html = '';
  if (data.alerts.length === 0) {
    html = '<div class="alert ok">All areas are supplied and all pipelines are working.</div>';
  }
  data.alerts.forEach(function (a) {
    html += '<div class="alert ' + a.level + '">' + esc(a.text) + '</div>';
  });
  el('alerts').innerHTML = html;

  // repair queue summary
  const m = data.maintenance;
  html = '';
  if (m.current) {
    html += '<div class="job active"><div><div class="title">Repairing pipeline ' + esc(m.current.pipelineId) + '</div>' +
            '<div class="meta">Job #' + m.current.id + ', started ' + esc(m.current.started) + '</div></div>' +
            '<span class="badge s-IN_PROGRESS">IN PROGRESS</span></div>';
  }
  if (m.pending.length > 0) {
    html += '<p style="margin:10px 0 0"><span class="pos">' + m.pending.length + '</span> &nbsp;job(s) waiting. Next in line: <b>pipeline ' +
            esc(m.pending[0].pipelineId) + '</b></p>';
  }
  if (!m.current && m.pending.length === 0) {
    html = '<div class="alert ok">No repair jobs. All pipelines are working.</div>';
  }
  html += '<p style="margin:12px 0 0"><button class="small" onclick="showTab(\'maintenance\')">Open maintenance</button></p>';
  el('queueSummary').innerHTML = html;

  // reservoir levels
  html = '';
  data.reservoirs.forEach(function (r) {
    const percent = r.capacity > 0 ? (r.remaining / r.capacity) * 100 : 0;
    html += '<div class="level"><div class="top"><b>' + esc(r.name) + ' (' + esc(r.id) + ')</b>' +
            '<span>' + fmt(r.remaining) + ' / ' + fmt(r.capacity) + ' L left</span></div>' +
            '<div class="bar"><div style="width:' + percent + '%;background:' + barColor(percent) + '"></div></div></div>';
  });
  el('levels').innerHTML = html || '<div class="empty">No reservoirs yet.</div>';

  // priority-wise status
  html = '';
  ['CRITICAL', 'HIGH', 'NORMAL'].forEach(function (level) {
    const list = data.areas.filter(function (a) { return a.priority === level; });
    const supplied = list.filter(function (a) { return a.status === 'SUPPLIED'; }).length;
    let unmet = 0;
    list.forEach(function (a) { unmet += a.unmet; });
    html += '<div class="prio"><span><span class="badge p-' + level + '">' + level + '</span> &nbsp;' +
            supplied + ' of ' + list.length + ' fully supplied</span>' +
            (unmet > 0 ? '<b style="color:var(--red)">' + fmt(unmet) + ' L unmet</b>'
                       : '<span style="color:var(--green)">no shortage</span>') + '</div>';
  });
  el('priorities').innerHTML = html;
  el('policyNote').textContent = 'Allocation rule: ' + data.policy + '.';

  // area-wise allocation table
  html = '';
  data.areas.forEach(function (a) {
    const percent = a.demand > 0 ? (a.allocated / a.demand) * 100 : 100;
    const statusClass = a.status === 'LOW SUPPLY' ? 'LOW' : a.status;
    html += '<tr><td><b>' + esc(a.name) + '</b><br><small class="sub">' + esc(a.id) + ' &middot; ' + esc(a.details) + '</small></td>' +
            '<td><span class="badge p-' + a.priority + '">' + a.priority + '</span></td>' +
            '<td>' + fmt(a.demand) + '</td><td>' + fmt(a.allocated) + '</td>' +
            '<td>' + (a.unmet > 0 ? '<b style="color:var(--red)">' + fmt(a.unmet) + '</b>' : '0') + '</td>' +
            '<td>' + routeText(a) + '</td>' +
            '<td><span class="badge s-' + statusClass + '">' + a.status + '</span></td></tr>';
  });
  el('allocationRows').innerHTML = html || '<tr><td colspan="7" class="empty">No areas yet.</td></tr>';

  // pipeline cards
  html = '';
  data.pipelines.forEach(function (p) {
    const failed = p.status === 'FAILED';
    html += '<div class="pipe"><div class="name">' + esc(p.id) + '</div>' +
            '<div class="ends">' + esc(p.from) + ' &harr; ' + esc(p.to) + '</div>' +
            '<div class="row"><span class="badge s-' + p.status + '">' + p.status + '</span>' +
            (failed ? '' : '<button class="small op-only" onclick="failPipeline(\'' + p.id + '\')">Fail</button>') + '</div>' +
            (failed ? '<div class="sub" style="margin-top:6px">' + esc(jobFor(p.id)) + '</div>' : '') + '</div>';
  });
  el('pipeCards').innerHTML = html || '<div class="empty">No pipelines yet.</div>';
}

function drawReservoirTable() {
  let html = '';
  data.reservoirs.forEach(function (r) {
    html += '<tr><td><b>' + esc(r.id) + '</b></td><td>' + esc(r.name) + '</td>' +
            '<td>' + fmt(r.capacity) + '</td><td>' + fmt(r.remaining) + '</td>' +
            '<td class="actions op-only"><button class="small" onclick="editReservoir(\'' + r.id + '\')">Edit</button> ' +
            '<button class="small danger" onclick="deleteReservoir(\'' + r.id + '\')">Delete</button></td></tr>';
  });
  el('reservoirRows').innerHTML = html || '<tr><td colspan="5" class="empty">No reservoirs yet.</td></tr>';
}

function drawAreaTable() {
  let html = '';
  data.areas.forEach(function (a) {
    const typeName = a.type === 'CRITICAL_INSTITUTION' ? 'Critical institution' : 'Residential';
    html += '<tr><td><b>' + esc(a.id) + '</b></td><td>' + esc(a.name) + '</td>' +
            '<td><span class="badge t-' + a.type + '">' + typeName + '</span></td>' +
            '<td>' + esc(a.details) + '</td>' +
            '<td><span class="badge p-' + a.priority + '">' + a.priority + '</span></td>' +
            '<td>' + fmt(a.demand) + '</td><td>' + fmt(a.allocated) + '</td>' +
            '<td class="actions op-only"><button class="small" onclick="editArea(\'' + a.id + '\')">Edit</button> ' +
            '<button class="small danger" onclick="deleteArea(\'' + a.id + '\')">Delete</button></td></tr>';
  });
  el('areaRows').innerHTML = html || '<tr><td colspan="8" class="empty">No areas yet.</td></tr>';
}

function drawPipelineTable() {
  let html = '';
  data.pipelines.forEach(function (p) {
    const failed = p.status === 'FAILED';
    html += '<tr><td><b>' + esc(p.id) + '</b></td><td>' + esc(p.from) + '</td><td>' + esc(p.to) + '</td>' +
            '<td><span class="badge s-' + p.status + '">' + p.status + '</span></td>' +
            '<td class="sub">' + esc(jobFor(p.id)) + '</td>' +
            '<td class="actions op-only">' + (failed ? '' : '<button class="small" onclick="failPipeline(\'' + p.id + '\')">Fail</button> ') +
            '<button class="small danger" onclick="deletePipeline(\'' + p.id + '\')">Delete</button></td></tr>';
  });
  el('pipelineRows').innerHTML = html || '<tr><td colspan="6" class="empty">No pipelines yet.</td></tr>';
}

// the From / To drop-downs list every reservoir and area
function fillPipelineSelects() {
  let options = '';
  data.reservoirs.forEach(function (r) {
    options += '<option value="' + esc(r.id) + '">' + esc(r.id) + ' - ' + esc(r.name) + '</option>';
  });
  data.areas.forEach(function (a) {
    options += '<option value="' + esc(a.id) + '">' + esc(a.id) + ' - ' + esc(a.name) + '</option>';
  });
  ['pFrom', 'pTo'].forEach(function (id) {
    const old = el(id).value;                 // keep what the user had chosen
    el(id).innerHTML = options;
    if (old) el(id).value = old;
  });
}

// ---------- maintenance (the repair queue) ----------

function drawMaintenance() {
  const m = data.maintenance;

  // number on the Maintenance tab
  const open = m.pending.length + (m.current ? 1 : 0);
  el('queueBadge').textContent = open;
  el('queueBadge').classList.toggle('hidden', open === 0);

  // the job being repaired now
  let html = '';
  if (m.current) {
    html = '<div class="job active"><div><div class="title">Pipeline ' + esc(m.current.pipelineId) + ' is being repaired</div>' +
           '<div class="meta">Job #' + m.current.id + ' &middot; reported ' + esc(m.current.created) +
           ' &middot; started ' + esc(m.current.started) + '</div></div>' +
           '<button class="primary op-only" onclick="completeJob()">Mark repair as complete</button></div>';
  } else if (m.pending.length > 0) {
    html = '<div class="job"><div><div class="title">The crew is free</div>' +
           '<div class="meta">Next job: pipeline ' + esc(m.pending[0].pipelineId) + ' (job #' + m.pending[0].id + ')</div></div>' +
           '<button class="primary op-only" onclick="startJob()">Start next job</button></div>';
  } else {
    html = '<div class="alert ok">No repair jobs. All pipelines are working.</div>';
  }
  el('currentJob').innerHTML = html;

  // waiting queue
  html = '';
  m.pending.forEach(function (r, i) {
    html += '<tr><td><span class="pos">' + (i + 1) + '</span></td><td>#' + r.id + '</td><td><b>' + esc(r.pipelineId) + '</b></td>' +
            '<td>' + esc(r.created) + '</td><td>' + esc(r.by) + '</td></tr>';
  });
  el('queueRows').innerHTML = html || '<tr><td colspan="5" class="empty">The queue is empty.</td></tr>';
  el('queueCount').textContent = '(' + m.pending.length + ' waiting)';

  // finished jobs
  html = '';
  m.history.forEach(function (r) {
    html += '<tr><td>#' + r.id + '</td><td><b>' + esc(r.pipelineId) + '</b></td>' +
            '<td><span class="badge s-' + r.status + '">' + r.status + '</span></td>' +
            '<td>' + esc(r.created) + '</td><td>' + esc(r.completed) + '</td></tr>';
  });
  el('historyRows').innerHTML = html || '<tr><td colspan="5" class="empty">Nothing finished yet.</td></tr>';
}

function startJob() {
  change('/api/maintenance/start', null, 'Repair started');
}

function completeJob() {
  change('/api/maintenance/complete', null, 'Repair complete - the pipeline works again');
}

// ---------- reservoirs: add / edit / delete ----------

el('reservoirForm').addEventListener('submit', async function (event) {
  event.preventDefault();
  const params = { id: el('rId').value, name: el('rName').value, capacity: el('rCap').value };
  let ok;
  if (editingReservoir) {
    ok = await change('/api/reservoir/update', params, 'Reservoir updated');
  } else {
    ok = await change('/api/reservoir/add', params, 'Reservoir added');
  }
  if (ok) resetReservoirForm();
});

function editReservoir(id) {
  const r = data.reservoirs.find(function (x) { return x.id === id; });
  editingReservoir = id;
  el('rId').value = r.id;
  el('rId').readOnly = true;
  el('rName').value = r.name;
  el('rCap').value = r.capacity;
  el('reservoirFormTitle').textContent = 'Edit reservoir ' + id;
  el('rSave').textContent = 'Save changes';
  el('rCancel').classList.remove('hidden');
  el('rName').focus();
}

function resetReservoirForm() {
  editingReservoir = null;
  el('reservoirForm').reset();
  el('rId').readOnly = false;
  el('reservoirFormTitle').textContent = 'Add a reservoir';
  el('rSave').textContent = 'Add reservoir';
  el('rCancel').classList.add('hidden');
}
el('rCancel').addEventListener('click', resetReservoirForm);

function deleteReservoir(id) {
  if (confirm('Delete reservoir ' + id + '? The pipelines joined to it will be deleted too.')) {
    change('/api/reservoir/delete', { id: id }, 'Reservoir deleted');
  }
}

// ---------- areas: add / edit / delete ----------

// the form shows different boxes for the two kinds of area
function updateAreaFormBoxes() {
  const isInstitution = el('aType').value === 'CRITICAL_INSTITUTION';
  el('aInstitutionBox').classList.toggle('hidden', !isInstitution);
  el('aPriorityBox').classList.toggle('hidden', isInstitution);
  el('aHouseholdsBox').classList.toggle('hidden', isInstitution);
}
el('aType').addEventListener('change', updateAreaFormBoxes);

el('areaForm').addEventListener('submit', async function (event) {
  event.preventDefault();
  const params = {
    id: el('aId').value, name: el('aName').value, demand: el('aDemand').value, type: el('aType').value,
    priority: el('aPriority').value, institutionType: el('aInstitution').value, households: el('aHouseholds').value
  };
  let ok;
  if (editingArea) {
    ok = await change('/api/area/update', params, 'Area updated');
  } else {
    ok = await change('/api/area/add', params, 'Area added');
  }
  if (ok) resetAreaForm();
});

function editArea(id) {
  const a = data.areas.find(function (x) { return x.id === id; });
  editingArea = id;
  el('aId').value = a.id;
  el('aId').readOnly = true;
  el('aName').value = a.name;
  el('aDemand').value = a.demand;
  el('aType').value = a.type;
  if (a.type === 'CRITICAL_INSTITUTION') {
    el('aInstitution').value = a.institutionType;
  } else {
    el('aPriority').value = a.priority;
    el('aHouseholds').value = a.households;
  }
  updateAreaFormBoxes();
  el('areaFormTitle').textContent = 'Edit area ' + id;
  el('aSave').textContent = 'Save changes';
  el('aCancel').classList.remove('hidden');
  el('aName').focus();
}

function resetAreaForm() {
  editingArea = null;
  el('areaForm').reset();
  el('aId').readOnly = false;
  updateAreaFormBoxes();
  el('areaFormTitle').textContent = 'Add a demand area';
  el('aSave').textContent = 'Add area';
  el('aCancel').classList.add('hidden');
}
el('aCancel').addEventListener('click', resetAreaForm);

function deleteArea(id) {
  if (confirm('Delete area ' + id + '? The pipelines joined to it will be deleted too.')) {
    change('/api/area/delete', { id: id }, 'Area deleted');
  }
}

// ---------- pipelines: add / fail / delete ----------

el('pipelineForm').addEventListener('submit', async function (event) {
  event.preventDefault();
  const ok = await change('/api/pipeline/add', { id: el('pId').value, from: el('pFrom').value, to: el('pTo').value }, 'Pipeline added');
  if (ok) el('pId').value = '';
});

function failPipeline(id) {
  change('/api/pipeline/fail', { id: id }, 'Pipeline ' + id + ' failed - a repair job was added to the queue');
}

function deletePipeline(id) {
  if (confirm('Delete pipeline ' + id + '?')) {
    change('/api/pipeline/delete', { id: id }, 'Pipeline deleted');
  }
}

// ---------- event log and reset ----------

function eventClass(type) {
  if (type.includes('FAILED') || type.includes('DELETE') || type.includes('CANCELLED')) return 'e-bad';
  if (type.includes('REPAIRED') || type.includes('ADD')) return 'e-good';
  if (type.includes('CHANGE') || type.includes('UPDATE')) return 'e-change';
  if (type.includes('READING')) return 'e-change';
  if (type.includes('REPAIR') || type.includes('LOGIN') || type.includes('LOGOUT') || type.includes('DAY')) return 'e-info';
  return 'e-system';
}

async function loadEvents() {
  try {
    const result = await callApi('/api/events', null, false);
    let html = '';
    result.events.forEach(function (e) {
      html += '<tr><td style="white-space:nowrap">' + esc(e.time) + '</td>' +
              '<td><span class="badge ' + eventClass(e.type) + '">' + esc(e.type) + '</span></td>' +
              '<td>' + esc(e.details) + '</td><td>' + esc(e.user) + '</td></tr>';
    });
    el('eventRows').innerHTML = html || '<tr><td colspan="4" class="empty">No events yet.</td></tr>';
    el('eventCount').textContent = '(' + result.total + ' events recorded, newest first)';
  } catch (e) {
    toast(e.message, true);
  }
}

el('resetBtn').addEventListener('click', function () {
  if (confirm('Reset the database to the sample data? Your own changes and the repair queue will be lost (the event history is kept).')) {
    resetReservoirForm();
    resetAreaForm();
    change('/api/reset', null, 'Database reset to the sample data');
  }
});

// ---------- account: password and log out ----------

el('passwordForm').addEventListener('submit', async function (event) {
  event.preventDefault();
  if (el('newPw').value !== el('newPw2').value) {
    toast('The two new passwords are not the same.', true);
    return;
  }
  try {
    await callApi('/api/password', { oldPassword: el('oldPw').value, newPassword: el('newPw').value }, true);
    el('passwordForm').reset();
    toast('Password changed', false);
  } catch (e) {
    toast(e.message, true);
  }
});

el('logoutBtn').addEventListener('click', async function () {
  try {
    await callApi('/api/logout', null, true);
  } catch (e) {
    // even if this fails, go to the login page
  }
  goToLogin();
});


// ---------- the supply route, shown as text ----------

// e.g. R1 -> A2 -> A4 -> A3, or a warning when the area is cut off
function routeText(a) {
  if (a.path.length === 0) {
    return '<span class="route cut">no working route</span>';
  }
  return '<span class="route">' + a.path.map(esc).join(' &rarr; ') + '</span>';
}

// ---------- simulated day ----------

function drawDay() {
  el('dayNo').textContent = data.day;
}

el('newDayBtn').addEventListener('click', function () {
  if (confirm('Start day ' + (data.day + 1) + '? The reservoirs are refilled and every area asks for its daily demand again.')) {
    change('/api/day/new', null, 'Day ' + (data.day + 1) + ' started');
  }
});

// ---------- manual readings ----------

function fillReadingSelects() {
  fillSelect('srReservoir', data.reservoirs.map(function (r) { return [r.id, r.id + ' - ' + r.name]; }));
  fillSelect('drArea', data.areas.map(function (a) { return [a.id, a.id + ' - ' + a.name]; }));
}

// refills a drop-down but keeps what the user had chosen
function fillSelect(id, items) {
  const old = el(id).value;
  el(id).innerHTML = items.map(function (it) { return '<option value="' + esc(it[0]) + '">' + esc(it[1]) + '</option>'; }).join('');
  if (old) el(id).value = old;
}

el('supplyReadingForm').addEventListener('submit', async function (event) {
  event.preventDefault();
  const ok = await change('/api/reservoir/reading', { id: el('srReservoir').value, supply: el('srValue').value }, 'Supply reading recorded');
  if (ok) el('srValue').value = '';
});

el('demandReadingForm').addEventListener('submit', async function (event) {
  event.preventDefault();
  const ok = await change('/api/area/reading', { id: el('drArea').value, demand: el('drValue').value }, 'Demand reading recorded');
  if (ok) el('drValue').value = '';
});

// ---------- the network map ----------

// Places for the sample town's nodes, so the picture is tidy. Anything you add
// yourself is placed near the places it is joined to.
const SAMPLE_POS = {
  R1: [80, 270], A1: [210, 130], A3: [395, 85], A4: [380, 380], A2: [210, 420],
  A5: [530, 270], A7: [660, 130], A8: [820, 210], A6: [800, 400], R2: [650, 420]
};
let selected = null;        // {kind: 'area' | 'reservoir' | 'pipe', id}
let layoutKey = '';
let positions = {};

function computePositions() {
  const ids = data.reservoirs.map(function (r) { return r.id; }).concat(data.areas.map(function (a) { return a.id; }));
  const key = ids.join(',') + '|' + data.pipelines.map(function (p) { return p.from + p.to; }).join(',');
  if (key === layoutKey) return;
  layoutKey = key;
  positions = {};
  const extras = [];
  ids.forEach(function (id) {
    if (SAMPLE_POS[id]) positions[id] = { x: SAMPLE_POS[id][0], y: SAMPLE_POS[id][1], fixed: true };
    else extras.push(id);
  });
  // start each new node next to the nodes it is joined to
  extras.forEach(function (id, i) {
    let sx = 0, sy = 0, n = 0;
    data.pipelines.forEach(function (p) {
      const other = p.from === id ? p.to : (p.to === id ? p.from : null);
      if (other && positions[other]) { sx += positions[other].x; sy += positions[other].y; n++; }
    });
    const angle = (id.charCodeAt(id.length - 1) * 37) % 360 * Math.PI / 180;
    if (n > 0) positions[id] = { x: sx / n + 70 * Math.cos(angle), y: sy / n + 70 * Math.sin(angle) };
    else positions[id] = { x: 90 + (i * 110) % 720, y: 500 };
  });
  // push the new nodes away from each other and from the others
  for (let step = 0; step < 150; step++) {
    extras.forEach(function (id) {
      let fx = 0, fy = 0;
      ids.forEach(function (other) {
        if (other === id) return;
        const dx = positions[id].x - positions[other].x, dy = positions[id].y - positions[other].y;
        const d = Math.max(Math.sqrt(dx * dx + dy * dy), 1);
        if (d < 110) { fx += dx / d * (110 - d) * 0.2; fy += dy / d * (110 - d) * 0.2; }
      });
      positions[id].x = Math.min(860, Math.max(40, positions[id].x + fx));
      positions[id].y = Math.min(495, Math.max(40, positions[id].y + fy));
    });
  }
}

function statusColor(status) {
  if (status === 'SUPPLIED') return 'var(--green)';
  if (status === 'LOW SUPPLY') return 'var(--amber)';
  if (status === 'SHORTAGE') return 'var(--red)';
  return 'var(--purple)';
}

function drawMap() {
  if (!isTabOpen('map')) return;
  computePositions();

  // the route to highlight (the supply path of the selected area)
  let route = [];
  if (selected && selected.kind === 'area') {
    const a = data.areas.find(function (x) { return x.id === selected.id; });
    if (a) route = a.path;
  }
  function onRoute(p) {
    for (let i = 0; i + 1 < route.length; i++) {
      if ((route[i] === p.from && route[i + 1] === p.to) || (route[i] === p.to && route[i + 1] === p.from)) return true;
    }
    return false;
  }

  let svg = '';
  // 1. the pipes
  data.pipelines.forEach(function (p) {
    const a = positions[p.from], b = positions[p.to];
    if (!a || !b) return;
    if (onRoute(p)) svg += '<line class="m-route" x1="' + a.x + '" y1="' + a.y + '" x2="' + b.x + '" y2="' + b.y + '"/>';
    svg += '<line class="m-edge' + (p.status === 'FAILED' ? ' failed' : '') + '" x1="' + a.x + '" y1="' + a.y + '" x2="' + b.x + '" y2="' + b.y + '"/>';
    const mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2;
    if (p.status === 'FAILED') svg += '<text class="m-x" x="' + mx + '" y="' + my + '">&#10005;</text>';
    svg += '<text class="m-name" x="' + mx + '" y="' + (my - 8) + '">' + esc(p.id) + '</text>';
    svg += '<line class="m-hit" data-pipe="' + esc(p.id) + '" x1="' + a.x + '" y1="' + a.y + '" x2="' + b.x + '" y2="' + b.y + '"/>';
    if (selected && selected.kind === 'pipe' && selected.id === p.id) {
      svg += '<line class="m-selected" x1="' + a.x + '" y1="' + a.y + '" x2="' + b.x + '" y2="' + b.y + '" style="stroke-width:10;opacity:.25"/>';
    }
  });
  // 2. the reservoirs
  data.reservoirs.forEach(function (r) {
    const p = positions[r.id];
    if (!p) return;
    svg += '<g class="m-node" data-node="reservoir:' + esc(r.id) + '"><rect class="m-res" x="' + (p.x - 30) + '" y="' + (p.y - 19) + '" width="60" height="38" rx="9"/>' +
           '<text class="m-res-id" x="' + p.x + '" y="' + p.y + '">' + esc(r.id) + '</text>' +
           '<text class="m-name" x="' + p.x + '" y="' + (p.y + 34) + '">' + esc(r.name) + '</text></g>';
    if (selected && selected.kind === 'reservoir' && selected.id === r.id) {
      svg += '<rect class="m-selected" x="' + (p.x - 35) + '" y="' + (p.y - 24) + '" width="70" height="48" rx="12"/>';
    }
  });
  // 3. the areas (the coloured ring shows their status)
  data.areas.forEach(function (a) {
    const p = positions[a.id];
    if (!p) return;
    const fill = a.type === 'CRITICAL_INSTITUTION' ? 'rgba(15,157,154,.18)' : 'var(--card)';
    svg += '<g class="m-node" data-node="area:' + esc(a.id) + '"><circle cx="' + p.x + '" cy="' + p.y + '" r="25" fill="' + fill +
           '" stroke="' + statusColor(a.status) + '" stroke-width="5"/>' +
           '<text class="m-id" x="' + p.x + '" y="' + p.y + '">' + esc(a.id) + '</text>' +
           '<text class="m-name" x="' + p.x + '" y="' + (p.y + 42) + '">' + esc(a.name) + '</text></g>';
    if (selected && selected.kind === 'area' && selected.id === a.id) {
      svg += '<circle class="m-selected" cx="' + p.x + '" cy="' + p.y + '" r="32"/>';
    }
  });
  el('mapSvg').innerHTML = svg;
  drawMapPanel();
}

// clicking on the map (one listener for the whole picture)
el('mapSvg').addEventListener('click', function (event) {
  const node = event.target.closest('[data-node]');
  const pipe = event.target.closest('[data-pipe]');
  if (node) {
    const parts = node.dataset.node.split(':');
    selected = { kind: parts[0], id: parts[1] };
  } else if (pipe) {
    selected = { kind: 'pipe', id: pipe.dataset.pipe };
  } else {
    selected = null;
  }
  drawMap();
});

function row(label, value) {
  return '<div class="m-panel-row"><span class="sub">' + label + '</span><span>' + value + '</span></div>';
}

function drawMapPanel() {
  let html = '';
  if (selected && selected.kind === 'area') {
    const a = data.areas.find(function (x) { return x.id === selected.id; });
    if (a) {
      const statusClass = a.status === 'LOW SUPPLY' ? 'LOW' : a.status;
      html = '<h2>' + esc(a.name) + '</h2>' +
        '<p style="margin:0 0 10px"><span class="badge t-' + a.type + '">' + (a.type === 'CRITICAL_INSTITUTION' ? 'Critical institution' : 'Residential') +
        '</span> <span class="badge p-' + a.priority + '">' + a.priority + '</span> <span class="badge s-' + statusClass + '">' + a.status + '</span></p>' +
        row('Details', esc(a.details)) + row('Daily demand', fmt(a.demand) + ' L') + row('Received', fmt(a.allocated) + ' L') +
        row('Unmet', a.unmet > 0 ? '<b style="color:var(--red)">' + fmt(a.unmet) + ' L</b>' : '0 L') +
        row('Supplied by', a.sources.length ? a.sources.map(function (s) { return esc(s.reservoir) + ' (' + fmt(s.litres) + ' L)'; }).join(', ') : 'nobody') +
        row('Supply route', routeText(a)) +
        '<p class="note">The blue line on the map is this route. BFS finds the route with the fewest pipelines that all work.</p>';
    }
  } else if (selected && selected.kind === 'reservoir') {
    const r = data.reservoirs.find(function (x) { return x.id === selected.id; });
    if (r) {
      html = '<h2>' + esc(r.name) + '</h2>' + row('Daily supply', fmt(r.capacity) + ' L') + row('Given out', fmt(r.allocated) + ' L') +
        row('Left today', fmt(r.remaining) + ' L');
      let list = '';
      data.areas.forEach(function (a) {
        a.sources.forEach(function (s) { if (s.reservoir === r.id) list += '<div class="m-panel-row"><span>' + esc(a.name) + '</span><span>' + fmt(s.litres) + ' L</span></div>'; });
      });
      html += '<h2 style="margin-top:14px;font-size:14px">Supplying</h2>' + (list || '<div class="empty">nothing today</div>');
    }
  } else if (selected && selected.kind === 'pipe') {
    const p = data.pipelines.find(function (x) { return x.id === selected.id; });
    if (p) {
      html = '<h2>Pipeline ' + esc(p.id) + '</h2><p style="margin:0 0 10px"><span class="badge s-' + p.status + '">' + p.status + '</span></p>' +
        row('Joins', esc(p.from) + ' &harr; ' + esc(p.to)) + row('Repair job', esc(jobFor(p.id)) || 'none');
      if (p.status === 'WORKING') {
        html += '<p style="margin-top:14px"><button class="danger op-only" onclick="failPipeline(\'' + p.id + '\')">Fail this pipeline</button></p>';
      } else {
        html += '<p class="note">It is repaired from the Maintenance page.</p>';
      }
    }
  }
  if (!html) {
    html = '<h2>Details</h2><p class="note" style="margin:0">Click an area, a reservoir or a pipe on the map.</p>' +
           '<p class="note">An area\'s coloured ring shows whether it gets its water. Select an area to see the route its water takes.</p>';
  }
  el('mapPanel').innerHTML = html;
}

// ---------- allocation history ----------

let openSnapshots = {};      // which snapshots have their details open

async function loadHistoryAll() {
  loadEvents();
  try {
    const result = await callApi('/api/history', null, false);
    let html = '';
    result.records.forEach(function (r) {
      html += '<tr><td>' + r.id + '</td><td style="white-space:nowrap">' + esc(r.time) + '</td><td>' + r.day + '</td>' +
              '<td><span class="badge ' + eventClass(r.trigger) + '">' + esc(r.trigger) + '</span></td><td>' + esc(r.user) + '</td>' +
              '<td>' + fmt(r.allocated) + ' / ' + fmt(r.available) + '</td>' +
              '<td>' + (r.unmet > 0 ? '<b style="color:var(--red)">' + fmt(r.unmet) + '</b>' : '0') + '</td>' +
              '<td>' + r.disconnected + '</td>' +
              '<td class="actions"><button class="small" onclick="toggleSnapshot(' + r.id + ')">' + (openSnapshots[r.id] ? 'Hide' : 'Details') + '</button></td></tr>';
      if (openSnapshots[r.id]) {
        let inner = '<table><thead><tr><th>Area</th><th>Priority</th><th>Demand (L)</th><th>Received (L)</th><th>Status</th></tr></thead><tbody>';
        r.lines.forEach(function (l) {
          const cls = l.status === 'LOW SUPPLY' ? 'LOW' : l.status;
          inner += '<tr><td>' + esc(l.id) + ' ' + esc(l.name) + '</td><td><span class="badge p-' + l.priority + '">' + l.priority + '</span></td>' +
                   '<td>' + fmt(l.demand) + '</td><td>' + fmt(l.allocated) + '</td><td><span class="badge s-' + cls + '">' + l.status + '</span></td></tr>';
        });
        html += '<tr class="detail"><td colspan="9">' + inner + '</tbody></table></td></tr>';
      }
    });
    el('allocRows').innerHTML = html || '<tr><td colspan="9" class="empty">No snapshots yet.</td></tr>';
    el('allocCount').textContent = '(latest ' + result.records.length + ', newest first)';
  } catch (e) {
    toast(e.message, true);
  }
}

function toggleSnapshot(id) {
  openSnapshots[id] = !openSnapshots[id];
  loadHistoryAll();
}

// ---------- tabs ----------

function isTabOpen(name) {
  return !el('tab-' + name).classList.contains('hidden');
}

function showTab(name) {
  document.querySelectorAll('.tab').forEach(function (t) { t.classList.add('hidden'); });
  document.querySelectorAll('nav button').forEach(function (b) {
    b.classList.toggle('active', b.dataset.tab === name);
  });
  el('tab-' + name).classList.remove('hidden');
  if (name === 'history') loadHistoryAll();
  if (name === 'map') drawMap();
}

el('nav').addEventListener('click', function (event) {
  const button = event.target.closest('button');
  if (button && button.dataset.tab) showTab(button.dataset.tab);
});

// ---------- start ----------

async function start() {
  try {
    me = await callApi('/api/me', null, false);     // goes to the login page if we are not logged in
  } catch (e) {
    return;
  }
  el('userName').textContent = me.fullName;
  el('userRole').textContent = me.role === 'OPERATOR' ? 'Operator' : 'Viewer (view only)';
  el('accName').textContent = me.fullName;
  el('accUser').textContent = me.username;
  el('accRole').textContent = me.role === 'OPERATOR' ? 'Operator' : 'Viewer';
  el('accNote').textContent = me.role === 'OPERATOR'
    ? 'Operators can add, edit and delete data and run the repair queue.'
    : 'Viewers can look at everything but cannot change anything.';
  if (me.role !== 'OPERATOR') {
    document.body.classList.add('viewer');            // hides every button that changes data
  }
  updateAreaFormBoxes();
  load();
  setInterval(load, 3000);   // refresh every 3 seconds
}
start();
