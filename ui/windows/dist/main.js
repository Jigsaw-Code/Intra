const el = {
  protectionState: document.getElementById("protectionState"),
  stateMessage: document.getElementById("stateMessage"),
  toggleButton: document.getElementById("toggleButton"),
  serviceState: document.getElementById("serviceState"),
  journalState: document.getElementById("journalState"),
  wintunState: document.getElementById("wintunState"),
  logPath: document.getElementById("logPath"),
  diagnosticsButton: document.getElementById("diagnosticsButton"),
};

let currentStatus = null;

function api() {
  return window.go?.uiapi?.App;
}

async function refresh() {
  const app = api();
  if (!app) {
    render({
      protectionState: "Needs attention",
      serviceState: "unavailable",
      journalPresent: false,
      wintunPresent: false,
      logPath: "",
      message: "UI backend is not available.",
    });
    return;
  }
  render(await app.GetStatus());
}

function render(status) {
  currentStatus = status;
  el.protectionState.textContent = status.protectionState;
  el.serviceState.textContent = status.serviceState;
  el.journalState.textContent = status.journalPresent ? "present" : "absent";
  el.wintunState.textContent = status.wintunPresent ? "present" : "missing";
  el.logPath.textContent = status.logPath || "";
  el.stateMessage.textContent = status.message || "Secure DNS protection status";

  const busy = status.protectionState === "Starting" || status.protectionState === "Stopping";
  el.toggleButton.disabled = busy;
  el.toggleButton.textContent = status.protectionState === "Protected" ? "Turn off" : "Turn on";
}

async function toggleProtection() {
  const app = api();
  if (!app || !currentStatus) return;
  el.toggleButton.disabled = true;
  if (currentStatus.protectionState === "Protected") {
    render(await app.StopIntra());
  } else {
    render(await app.StartIntra());
  }
}

async function openDiagnostics() {
  const app = api();
  if (!app) return;
  await app.OpenDiagnostics();
}

el.toggleButton.addEventListener("click", toggleProtection);
el.diagnosticsButton.addEventListener("click", openDiagnostics);
refresh();
setInterval(refresh, 5000);
