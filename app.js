// My Planner — sign-in and live sync through your own Firebase project.
// Your data lives at users/<your uid>/... and the database rules only let you read or write it.
import { initializeApp } from "https://www.gstatic.com/firebasejs/10.14.1/firebase-app.js";
import {
  getAuth, GoogleAuthProvider, signInWithPopup, signInWithRedirect, getRedirectResult,
  onAuthStateChanged, signOut
} from "https://www.gstatic.com/firebasejs/10.14.1/firebase-auth.js";
import {
  initializeFirestore, persistentLocalCache, persistentMultipleTabManager,
  doc, collection, setDoc, deleteDoc, onSnapshot
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
    if (!started) { started = true; window.__resolvePlannerDb(makeDb(user.uid)); }
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
