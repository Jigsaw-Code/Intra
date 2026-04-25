const el = {
  drawer: document.getElementById("drawer"),
  drawerOverlay: document.getElementById("drawerOverlay"),
  drawerButton: document.getElementById("drawerButton"),
  navItems: [...document.querySelectorAll(".nav-item[data-view]")],
  navLinks: [...document.querySelectorAll(".nav-link[data-url]")],
  views: [...document.querySelectorAll(".view")],
  exitAppButton: document.getElementById("exitAppButton"),
  indicator: document.getElementById("indicator"),
  protectionState: document.getElementById("protectionState"),
  headerStatus: document.getElementById("headerStatus"),
  headerStatusText: document.getElementById("headerStatusText"),
  stateMessage: document.getElementById("stateMessage"),
  toggleSwitch: document.getElementById("toggleSwitch"),
  serviceState: document.getElementById("serviceState"),
  journalState: document.getElementById("journalState"),
  wintunState: document.getElementById("wintunState"),
  logPath: document.getElementById("logPath"),
  wintunHelp: document.getElementById("wintunHelp"),
  diagnosticsButton: document.getElementById("diagnosticsButton"),
  lifetimeQueriesValue: document.getElementById("lifetimeQueriesValue"),
  recentQueriesValue: document.getElementById("recentQueriesValue"),
  recentTransactionsCard: document.getElementById("recentTransactionsCard"),
  recentTransactionsList: document.getElementById("recentTransactionsList"),
  serverCardValue: document.getElementById("serverCardValue"),
  serverSummary: document.getElementById("serverSummary"),
  serverSelect: document.getElementById("serverSelect"),
  serverDescription: document.getElementById("serverDescription"),
  serverWebsiteButton: document.getElementById("serverWebsiteButton"),
  customServerUrl: document.getElementById("customServerUrl"),
  urlWarning: document.getElementById("urlWarning"),
  saveSettingsButton: document.getElementById("saveSettingsButton"),
  showRecentQueries: document.getElementById("showRecentQueries"),
};

let currentStatus = null;
let uiSettings = null;

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
      serverName: "dns.google",
      serverUrl: "https://dns.google/dns-query",
      message: "UI backend is not available.",
    });
    return;
  }
  render(await app.GetStatus());
}

async function loadSettings() {
  const app = api();
  if (!app) return;
  uiSettings = await app.GetSettings();
  renderSettings();
}

function render(status) {
  currentStatus = status;
  const on = status.protectionState === "Protected" || status.protectionState === "Starting";
  el.indicator.textContent = on ? "On" : "Off";
  el.protectionState.textContent = androidStatus(status.protectionState);
  el.headerStatus.dataset.state = status.protectionState;
  el.headerStatusText.textContent = androidStatus(status.protectionState);
  el.serviceState.textContent = status.serviceState;
  el.journalState.textContent = status.journalPresent ? "present" : "absent";
  el.wintunState.textContent = status.wintunPresent ? "present" : "missing";
  el.logPath.textContent = status.logPath || "";
  el.lifetimeQueriesValue.textContent = formatCount(status.lifetimeQueries || 0);
  el.recentQueriesValue.textContent = formatCount(status.recentQueries || 0);
  el.stateMessage.textContent = status.message || stateMessage(status);
  el.protectionState.dataset.state = status.protectionState;
  el.serverCardValue.textContent = status.serverName || "dns.google";
  el.serverSummary.textContent = `Currently ${status.serverName || "dns.google"}`;

  const busy = status.protectionState === "Starting" || status.protectionState === "Stopping";
  el.toggleSwitch.disabled = busy;
  el.toggleSwitch.checked = status.protectionState === "Protected" || status.protectionState === "Starting";

  el.wintunHelp.classList.toggle("hidden", status.wintunPresent);
  el.wintunHelp.textContent = status.wintunPresent
    ? ""
    : "Place the amd64 wintun.dll in the same folder as the Intra Windows executables, then restart Intra.";
  renderRecentTransactions(status.recentTransactions || []);
}

function renderSettings() {
  if (!uiSettings) return;
  el.serverSelect.replaceChildren();
  uiSettings.servers.forEach((server, index) => {
    const option = document.createElement("option");
    option.value = String(index);
    option.textContent = server.name;
    el.serverSelect.appendChild(option);
  });
  const selectedIndex = uiSettings.servers.findIndex((server) => server.url === uiSettings.selectedUrl);
  if (selectedIndex >= 0) {
    document.querySelector('input[name="serverMode"][value="builtin"]').checked = true;
    el.serverSelect.value = String(selectedIndex);
  } else {
    document.querySelector('input[name="serverMode"][value="custom"]').checked = true;
    el.customServerUrl.value = uiSettings.selectedUrl || "";
  }
  el.showRecentQueries.checked = uiSettings.showRecentQueries;
  updateServerChoice();
}

function renderRecentTransactions(transactions) {
  const show = Boolean(uiSettings?.showRecentQueries);
  el.recentTransactionsCard.classList.toggle("hidden", !show);
  el.recentTransactionsList.replaceChildren();
  if (!show) return;
  if (transactions.length === 0) {
    const empty = document.createElement("p");
    empty.className = "empty-list";
    empty.textContent = "No recent queries yet.";
    el.recentTransactionsList.appendChild(empty);
    return;
  }
  [...transactions].reverse().forEach((tx) => {
    const row = document.createElement("div");
    row.className = "transaction-row";
    const title = document.createElement("div");
    title.className = "transaction-title";
    title.textContent = tx.name || "(unknown query)";
    const meta = document.createElement("div");
    meta.className = "transaction-meta";
    meta.textContent = `${formatTime(tx.responseTime)} · ${typeName(tx.type)} · ${formatLatency(tx.latencyMs)} · ${statusName(tx.status)}`;
    row.append(title, meta);
    el.recentTransactionsList.appendChild(row);
  });
}

function updateServerChoice() {
  if (!uiSettings) return;
  const custom = selectedServerMode() === "custom";
  el.serverSelect.disabled = custom;
  el.customServerUrl.disabled = !custom;
  el.serverWebsiteButton.disabled = custom;
  const server = uiSettings.servers[Number(el.serverSelect.value || 0)];
  el.serverDescription.textContent = custom
    ? "Please enter a DNS-over-HTTPS endpoint."
    : `${server.description} Please visit their website to review their privacy policy and any applicable terms of service.`;
  el.urlWarning.classList.toggle("hidden", !custom || isValidCustomUrl(el.customServerUrl.value));
}

async function toggleProtection() {
  const app = api();
  if (!app || !currentStatus) return;
  el.toggleSwitch.disabled = true;
  if (currentStatus.protectionState === "Protected" || currentStatus.protectionState === "Starting") {
    render(await app.StopIntra());
  } else {
    render(await app.StartIntra());
  }
}

async function saveSettings() {
  const app = api();
  if (!app || !uiSettings) return;
  const custom = selectedServerMode() === "custom";
  const selected = uiSettings.servers[Number(el.serverSelect.value || 0)];
  const dohUrl = custom ? el.customServerUrl.value.trim() : selected.url;
  const dohIps = custom ? "" : selected.ips;
  if (!isValidCustomUrl(dohUrl)) {
    el.urlWarning.classList.remove("hidden");
    return;
  }
  el.saveSettingsButton.disabled = true;
  try {
    render(await app.SaveSettings({ dohUrl, dohIps, showRecentQueries: el.showRecentQueries.checked }));
    await loadSettings();
    await refresh();
  } finally {
    el.saveSettingsButton.disabled = false;
  }
}

async function saveRecentQuerySetting() {
  if (!uiSettings) return;
  const selectedIndex = Number(el.serverSelect.value || 0);
  const selected = uiSettings.servers[selectedIndex];
  const custom = selectedServerMode() === "custom";
  const dohUrl = custom ? el.customServerUrl.value.trim() : selected.url;
  const dohIps = custom ? "" : selected.ips;
  const app = api();
  if (!app) return;
  await app.SaveSettings({ dohUrl, dohIps, showRecentQueries: el.showRecentQueries.checked });
  await loadSettings();
  await refresh();
}

async function openDiagnostics() {
  const app = api();
  if (!app) return;
  await app.OpenDiagnostics();
}

function openDrawer() {
  el.drawer.classList.add("open");
  el.drawerOverlay.classList.remove("hidden");
}

function closeDrawer() {
  el.drawer.classList.remove("open");
  el.drawerOverlay.classList.add("hidden");
}

function showView(name) {
  el.views.forEach((view) => view.classList.toggle("active", view.id === `${name}View`));
  el.navItems.forEach((item) => item.classList.toggle("active", item.dataset.view === name));
  closeDrawer();
}

async function exitApp() {
  const app = api();
  if (!app) return;
  await app.ExitApp();
}

function openURL(url) {
  if (window.runtime?.BrowserOpenURL) {
    window.runtime.BrowserOpenURL(url);
  } else {
    window.open(url, "_blank");
  }
  closeDrawer();
}

function selectedServerMode() {
  return document.querySelector('input[name="serverMode"]:checked')?.value || "builtin";
}

function isValidCustomUrl(raw) {
  try {
    const stripped = raw.trim().split("{")[0];
    const parsed = new URL(stripped);
    return parsed.protocol === "https:" && parsed.hostname && parsed.pathname && !parsed.search && !parsed.hash;
  } catch {
    return false;
  }
}

function formatCount(value) {
  return new Intl.NumberFormat().format(value);
}

function formatLatency(value) {
  if (!value) return "0 ms";
  return `${Math.round(value)} ms`;
}

function formatTime(value) {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  return date.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

function typeName(value) {
  switch (value) {
    case 1:
      return "A";
    case 28:
      return "AAAA";
    case 5:
      return "CNAME";
    case 15:
      return "MX";
    case 16:
      return "TXT";
    default:
      return value ? String(value) : "TYPE";
  }
}

function statusName(value) {
  return value === 0 ? "Complete" : `Status ${value}`;
}

function androidStatus(state) {
  switch (state) {
    case "Protected":
      return "Protected";
    case "Starting":
      return "Starting...";
    case "Stopping":
      return "Stopping...";
    case "Not protected":
      return "Exposed";
    default:
      return "Failing";
  }
}

function stateMessage(status) {
  switch (status.protectionState) {
    case "Protected":
      return "Your connection is protected from DNS attacks.";
    case "Starting":
      return "Hang tight. Intra is establishing a server connection.";
    case "Stopping":
      return "Intra is turning off protection and restoring network settings.";
    case "Not protected":
      return "Your connection is exposed to DNS attacks.";
    default:
      return "Warning: Queries may be failing. You may want to choose a different server or restart Intra.";
  }
}

el.drawerButton.addEventListener("click", openDrawer);
el.drawerOverlay.addEventListener("click", closeDrawer);
el.navItems.forEach((item) => item.addEventListener("click", () => showView(item.dataset.view)));
el.navLinks.forEach((item) => item.addEventListener("click", () => openURL(item.dataset.url)));
el.exitAppButton.addEventListener("click", exitApp);
el.toggleSwitch.addEventListener("change", toggleProtection);
el.diagnosticsButton.addEventListener("click", openDiagnostics);
el.serverSelect.addEventListener("change", updateServerChoice);
el.serverWebsiteButton.addEventListener("click", () => {
  const server = uiSettings?.servers[Number(el.serverSelect.value || 0)];
  if (server) openURL(server.website);
});
document.querySelectorAll('input[name="serverMode"]').forEach((input) => input.addEventListener("change", updateServerChoice));
el.customServerUrl.addEventListener("input", updateServerChoice);
el.saveSettingsButton.addEventListener("click", saveSettings);
el.showRecentQueries.addEventListener("change", saveRecentQuerySetting);

loadSettings();
refresh();
setInterval(refresh, 5000);
