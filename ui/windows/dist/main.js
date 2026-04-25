const el = {
  drawer: document.getElementById("drawer"),
  drawerOverlay: document.getElementById("drawerOverlay"),
  drawerButton: document.getElementById("drawerButton"),
  navItems: [...document.querySelectorAll(".drawer-item[data-view]")],
  navLinks: [...document.querySelectorAll(".drawer-link[data-url]")],
  views: [...document.querySelectorAll(".view")],
  exitAppButton: document.getElementById("exitAppButton"),
  indicator: document.getElementById("indicator"),
  protectionState: document.getElementById("protectionState"),
  statusHero: document.getElementById("statusHero"),
  stateMessage: document.getElementById("stateMessage"),
  toggleSwitch: document.getElementById("toggleSwitch"),
  transportIcon: document.getElementById("transportIcon"),
  transportValue: document.getElementById("transportValue"),
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
  const displayState = androidStatus(status.protectionState);
  el.indicator.textContent = on ? "On" : "Off";
  el.protectionState.textContent = displayState;
  el.protectionState.dataset.state = status.protectionState;
  el.statusHero.dataset.state = status.protectionState;
  el.serviceState.textContent = status.serviceState;
  el.journalState.textContent = status.journalPresent ? "present" : "absent";
  el.wintunState.textContent = status.wintunPresent ? "present" : "missing";
  el.logPath.textContent = status.logPath || "";
  el.lifetimeQueriesValue.textContent = formatCount(status.lifetimeQueries || 0);
  el.recentQueriesValue.textContent = formatCount(status.recentQueries || 0);
  el.stateMessage.textContent = status.message || stateMessage(status);
  el.serverCardValue.textContent = status.serverName || "dns.google";
  el.serverSummary.textContent = `Currently ${status.serverName || "dns.google"}`;
  el.transportIcon.src = on ? "./assets/ic_lock.svg" : "./assets/ic_lock_open.svg";
  el.transportValue.textContent = on ? "https" : "unknown";

  const busy = status.protectionState === "Starting" || status.protectionState === "Stopping";
  el.toggleSwitch.disabled = busy;
  el.toggleSwitch.checked = on;

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
    const row = document.createElement("article");
    row.className = "transaction-row";

    const condensed = document.createElement("button");
    condensed.type = "button";
    condensed.className = "transaction-condensed";
    condensed.setAttribute("aria-expanded", "false");

    const marker = document.createElement("span");
    marker.className = "transaction-marker";
    marker.textContent = statusMarker(tx.status);

    const hostname = document.createElement("span");
    hostname.className = "transaction-hostname";
    hostname.textContent = displayHostname(tx.name);
    hostname.title = tx.name || "";

    const time = document.createElement("span");
    time.className = "transaction-time";
    time.textContent = formatTime(tx.responseTime);

    const expander = document.createElement("span");
    expander.className = "transaction-expander";

    const details = document.createElement("div");
    details.className = "transaction-details";
    details.hidden = true;
    details.append(
      detailRow("./assets/ic_pageview.svg", "Query", tx.name || "(unknown query)"),
      detailRow("./assets/ic_dns.svg", "Type", typeName(tx.type)),
      detailRow("./assets/ic_access_time.svg", "Latency", formatLatency(tx.latencyMs)),
      detailRow("./assets/ic_location_on.svg", "Resolver location", tx.server || "(unknown server)"),
      detailRow("./assets/ic_server.svg", "Destination server", destinationValue(tx)),
    );

    condensed.addEventListener("click", () => {
      const expanded = row.classList.toggle("expanded");
      details.hidden = !expanded;
      condensed.setAttribute("aria-expanded", String(expanded));
    });

    condensed.append(marker, hostname, time, expander);
    row.append(condensed, details);
    el.recentTransactionsList.appendChild(row);
  });
}

function detailRow(icon, label, value) {
  const row = document.createElement("div");
  row.className = "transaction-detail-row";
  const iconEl = document.createElement("img");
  iconEl.src = icon;
  iconEl.alt = "";
  const copy = document.createElement("div");
  const labelEl = document.createElement("span");
  labelEl.textContent = label;
  const valueEl = document.createElement("strong");
  valueEl.textContent = value;
  copy.append(labelEl, valueEl);
  row.append(iconEl, copy);
  return row;
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

function displayHostname(name) {
  if (!name) return "(unknown query)";
  const trimmed = name.endsWith(".") ? name.slice(0, -1) : name;
  const parts = trimmed.split(".").filter(Boolean);
  if (parts.length >= 2) {
    return parts.slice(-2).join(".");
  }
  return trimmed;
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
  switch (value) {
    case 0:
      return "Complete";
    case 1:
      return "Send failed";
    case 2:
      return "HTTP error";
    case 3:
      return "Bad query";
    case 4:
      return "Bad response";
    case 5:
      return "Internal error";
    default:
      return `Status ${value}`;
  }
}

function statusMarker(value) {
  return value === 0 ? "OK" : "!";
}

function destinationValue(tx) {
  if (tx.status === 0) return tx.server || "(available after DNS response parsing)";
  return statusName(tx.status);
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
      return "Warning: Queries are failing. You may want to choose a different server or restart Intra.";
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
