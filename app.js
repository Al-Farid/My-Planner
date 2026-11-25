// My Planner — sign-in and live sync through your own Firebase project.
// Your data lives at users/<your uid>/... and the database rules only let you read or write it.
import { initializeApp } from "https://www.gstatic.com/firebasejs/10.14.1/firebase-app.js";
import {
  getAuth, GoogleAuthProvider, signInWithPopup, signInWithRedirect, getRedirectResult,
  onAuthStateChanged, signOut
} from "https://www.gstatic.com/firebasejs/10.14.1/firebase-auth.js";
import {
  initializeFirestore, persistentLocalCache, persistentMultipleTabManager,
  doc, collection, setDoc, deleteDoc, onSnapshot, getDoc, runTransaction
} from "https://www.gstatic.com/firebasejs/10.14.1/firebase-firestore.js";
import { firebaseConfig } from "./firebase-config.js";

const $ = (id) => document.getElementById(id);
const gate = $("gate"), gateErr = $("gateErr");

if (!firebaseConfig || !firebaseConfig.apiKey || firebaseConfig.apiKey.startsWith("PASTE")) {
  gateErr.textContent = "firebase-config.js still has the placeholder values. Paste your Firebase web config into it.";
  $("signInBtn").disabled = true;
  throw new Error("Missing Firebase config");
}

const app = initializeApp(firebaseConfig);
const auth = getAuth(app);
// Offline cache: the planner opens and works without a connection, and syncs when you are back online.
const fs = initializeFirestore(app, {
  ignoreUndefinedProperties: true,
  localCache: persistentLocalCache({ tabManager: persistentMultipleTabManager() })
});

// The planner was written against a small database interface (doc / collection / set / onSnapshot).
// This adapter maps it onto Firestore, scoped to the signed-in user.
function wrapDoc(ref) {
  return {
    set: (data) => setDoc(ref, data),
    // Read the server copy and write fn(serverCopy) in one step, so edits from other devices are never overwritten.
    merge: (fn) => runTransaction(fs, async (tx) => {
      const s = await tx.get(ref);
      const out = fn(s.exists() ? s.data() : null);
      tx.set(ref, out);
      return out;
    }),
    delete: () => deleteDoc(ref),
    onSnapshot: (cb, onErr) => onSnapshot(ref,
      (s) => cb({ exists: s.exists(), data: () => s.data(), metadata: s.metadata }), onErr)
  };
}
function makeDb(uid) {
  const base = "users/" + uid + "/";
  return {
    doc: (path) => wrapDoc(doc(fs, base + path)),
    collection: (path) => {
      const c = collection(fs, base + path);
      return {
        doc: (id) => wrapDoc(id ? doc(c, id) : doc(c)),
        onSnapshot: (cb, onErr) => onSnapshot(c,
          (qs) => cb({ docs: qs.docs.map((d) => ({ id: d.id, data: () => d.data() })) }), onErr)
      };
    }
  };
}

const provider = new GoogleAuthProvider();
provider.setCustomParameters({ prompt: "select_account" });

$("signInBtn").addEventListener("click", async () => {
  gateErr.textContent = "";
  try {
    await signInWithPopup(auth, provider);
  } catch (e) {
    const code = (e && e.code) || "";
    if (code === "auth/popup-blocked" || code === "auth/operation-not-supported-in-this-environment") {
      try { await signInWithRedirect(auth, provider); return; } catch (e2) { gateErr.textContent = describe(e2); return; }
    }
    if (code !== "auth/popup-closed-by-user" && code !== "auth/cancelled-popup-request") gateErr.textContent = describe(e);
  }
});
getRedirectResult(auth).catch((e) => { gateErr.textContent = describe(e); });

function describe(e) {
  const code = (e && e.code) || "";
  if (code === "auth/unauthorized-domain") return "This web address isn't allowed yet. In Firebase, add it under Authentication → Settings → Authorized domains.";
  if (code === "auth/network-request-failed") return "No connection. Check your internet and try again.";
  return "Sign-in didn't work (" + (code || "unknown error") + "). Try again.";
}

let started = false;
onAuthStateChanged(auth, (user) => {
  if (user) {
    gate.hidden = true;
    addAccountButton(user);
    if (!started) {
      started = true;
      window.__resolvePlannerDb(makeDb(user.uid));
      setupWidgetFeed(user.uid).then(addWidgetButton).catch(() => {});
    }
  } else {
    if (started) { location.reload(); return; } // signed out: reload into the sign-in screen
    gate.hidden = false;
  }
});

function addAccountButton(user) {
  if ($("acctBtn")) return;
  const b = document.createElement("button");
  b.id = "acctBtn"; b.className = "acct"; b.type = "button";
  b.title = "Signed in as " + (user.email || "you") + ". Click to sign out.";
  b.textContent = "Sign out";
  b.addEventListener("click", () => {
    if (b.dataset.confirm) { signOut(auth); return; }
    b.dataset.confirm = "1"; b.textContent = "Sign out?";
    setTimeout(() => { delete b.dataset.confirm; b.textContent = "Sign out"; }, 4000);
  });
  const top = document.querySelector(".top-r");
  if (top) top.appendChild(b);
}

// ---------- Home-screen widget ----------
// The widget reads a copy of your planner at feeds/<code>. The code is a long random key that only
// your devices know; the database rules let anyone holding it read that copy, and only you write it.
const APK_URL = "https://github.com/Al-Farid/My-Planner/releases/latest/download/PlannerWidget.apk";

function randomCode() {
  const a = new Uint8Array(18);
  crypto.getRandomValues(a);
  return Array.from(a, (b) => "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"[b % 62]).join("") + "w2";
}

/* First day a habit can count as missed: Mon 23 Nov 2026, or the day it was added if later. */
function habitStart(h) {
  let from = "2026-11-23";
  const ts = parseInt(String(h.id || "").slice(1, -4), 36);
  if (ts > 1.6e12 && ts < 4e12) {
    const c = new Date(ts);
    const cs = c.getFullYear() + "-" + String(c.getMonth() + 1).padStart(2, "0") + "-" + String(c.getDate()).padStart(2, "0");
    if (cs > from) from = cs;
  }
  return from;
}

/* Habits for the widget: each habit's name and the days it was done in the last three weeks. */
function habitFeed(d) {
  const hs = Array.isArray(d.habits) ? d.habits : [];
  const log = d.habitLog && typeof d.habitLog === "object" ? d.habitLog : {};
  const since = new Date(); since.setDate(since.getDate() - 21);
  const cut = since.getFullYear() + "-" + String(since.getMonth() + 1).padStart(2, "0") + "-" + String(since.getDate()).padStart(2, "0");
  return hs.map((h) => ({
    n: String(h.name || ""),
    f: habitStart(h),
    d: Object.keys(log).filter((k) => k >= cut && Array.isArray(log[k]) && log[k].includes(h.id)).sort()
  }));
}

function compactPlan(d, uid) {
  const tasks = Array.isArray(d.tasks) ? d.tasks : [];
  const sections = [];
  tasks.forEach((t) => { if (!sections.includes(t.section)) sections.push(t.section); });
  return {
    owner: uid,
    updated: d.updated || Date.now(),
    sections,
    tasks: tasks.map((t) => ({
      x: String(t.text || ""), s: sections.indexOf(t.section), d: !!t.done, p: !!t.priority,
      l: Array.isArray(t.slots) ? t.slots.filter((x) => typeof x === "string") : [],
      u: typeof t.due === "string" ? t.due : ""
    })),
    secDue: d.secDue && typeof d.secDue === "object" && !Array.isArray(d.secDue) ? d.secDue : {},
    notes: d.notes && typeof d.notes === "object" && !Array.isArray(d.notes) ? d.notes : {},
    blocks: d.blocks && typeof d.blocks === "object" && !Array.isArray(d.blocks) ? d.blocks : {},
    habits: habitFeed(d)
  };
}

let feedRef = null, feedUid = null, lastPlan = null, feedStatus = "Not sent to the widget yet.";
function setFeedStatus(t) { feedStatus = t; const el = $("wStatus"); if (el) el.textContent = t; }
async function publishFeed(forceServer) {
  if (!feedRef) return;
  try {
    if (forceServer || !lastPlan) {
      const s = await getDoc(doc(fs, "users/" + feedUid + "/tracker/state"));
      if (s.exists()) lastPlan = s.data();
    }
    if (!lastPlan) { setFeedStatus("Nothing to send yet: add a task first."); return; }
    await setDoc(feedRef, compactPlan(lastPlan, feedUid));
    setFeedStatus("Sent to the widget ✓ " + new Date().toLocaleTimeString([], { hour: "numeric", minute: "2-digit" }));
  } catch (e) {
    setFeedStatus(e && e.code === "permission-denied"
      ? "Blocked by Firebase rules. Add the feeds rule in Firebase → Firestore → Rules, publish it, then tap Send to widget now."
      : "Couldn't send to the widget (" + ((e && e.code) || "error") + ").");
  }
}

async function setupWidgetFeed(uid) {
  const sref = doc(fs, "users/" + uid + "/settings/widget");
  let code = null;
  try { const s = await getDoc(sref); code = s.exists() ? s.data().feedId : null; } catch (e) { /* offline: try later */ }
  if (!code) { code = randomCode(); await setDoc(sref, { feedId: code }, { merge: true }); }
  const fref = doc(fs, "feeds/" + code);
  let publishedOnce = false, lastPub = null;
  feedRef = fref; feedUid = uid;
  // Publish after your own edits, and once per app start from the server's copy.
  onSnapshot(doc(fs, "users/" + uid + "/tracker/state"), { includeMetadataChanges: true }, (s) => {
    if (!s.exists()) return;
    // Publish whenever the confirmed copy changes (from any device), and once per app start.
    if (s.metadata.fromCache || s.metadata.hasPendingWrites) return;
    const up = s.data().updated || 0;
    if (publishedOnce && up === lastPub) return;
    publishedOnce = true; lastPub = up;
    lastPlan = s.data();
    publishFeed();
  });
  // Calendars sent up by the Planner Widget app on the phone/tablet (read-only here).
  onSnapshot(doc(fs, "devcal/" + code), (s) => {
    window.__plannerCal = s.exists() ? s.data() : null;
    window.dispatchEvent(new Event("planner-calendar"));
  }, () => { /* rules not updated yet: calendar tab shows planner items only */ });
  return code;
}

function addWidgetButton(code) {
  if ($("widgetBtn") || !code) return;
  const b = document.createElement("button");
  b.id = "widgetBtn"; b.className = "acct"; b.type = "button"; b.textContent = "Widget";
  b.addEventListener("click", () => openWidgetPanel(code));
  const top = document.querySelector(".top-r");
  if (top) top.insertBefore(b, $("acctBtn") || null);
}

function openWidgetPanel(code) {
  let m = $("widgetModal");
  if (!m) {
    m = document.createElement("div");
    m.className = "modal"; m.id = "widgetModal";
    m.innerHTML = `
      <div class="sheet" role="dialog" aria-modal="true" aria-labelledby="wTitle">
        <h3 id="wTitle">Home-screen widget</h3>
        <p style="margin:0;color:var(--muted)">Shows this Weekly plan on your Android home screen and updates live.</p>
        <ol style="margin:0;padding-left:20px;display:flex;flex-direction:column;gap:6px">
          <li>Install the widget app: <a id="wApk" target="_blank" rel="noopener">download PlannerWidget.apk</a>, open it, and allow the install.</li>
          <li>On this device, tap <b>Connect widget on this device</b>. Or copy the code and paste it in the widget app.</li>
          <li>Long-press your home screen, tap <b>Widgets</b>, and add <b>Weekly plan</b>.</li>
        </ol>
        <label class="fld"><span>Widget code</span><input type="text" id="wCode" readonly></label>
        <p id="wStatus" style="margin:0;font-weight:600"></p>
        <div class="row-btns" style="flex-wrap:wrap">
          <button type="button" class="btn" id="wSend">Send to widget now</button>
          <button type="button" class="btn" id="wCopy">Copy code</button>
          <a class="btn primary" id="wConnect" style="text-decoration:none">Connect widget on this device</a>
          <button type="button" class="btn" id="wClose">Close</button>
        </div>
        <p style="margin:0;font-size:12px;color:var(--muted)">Anyone with this code can see your week, so keep it to your own devices.</p>
      </div>`;
    document.body.appendChild(m);
    m.addEventListener("click", (e) => { if (e.target === m) m.hidden = true; });
    m.querySelector("#wClose").addEventListener("click", () => { m.hidden = true; });
    m.querySelector("#wSend").addEventListener("click", () => { setFeedStatus("Sending…"); publishFeed(true); });
    m.querySelector("#wCopy").addEventListener("click", () => {
      const inp = m.querySelector("#wCode");
      const done = () => { m.querySelector("#wCopy").textContent = "Copied"; setTimeout(() => { m.querySelector("#wCopy").textContent = "Copy code"; }, 2000); };
      if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(inp.value).then(done, () => { inp.select(); });
      else inp.select();
    });
  }
  m.querySelector("#wApk").href = APK_URL;
  m.querySelector("#wCode").value = code;
  setFeedStatus(feedStatus);
  const intent = "intent://connect?feed=" + encodeURIComponent(code) +
    "#Intent;scheme=myplanner;package=io.github.alfarid.planner;S.browser_fallback_url=" + encodeURIComponent(APK_URL) + ";end";
  const c = m.querySelector("#wConnect");
  c.href = intent;
  c.hidden = !/Android/i.test(navigator.userAgent);
  m.hidden = false;
}
