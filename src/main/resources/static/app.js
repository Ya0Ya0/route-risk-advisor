"use strict";

// ---- Small DOM helpers -----------------------------------------------------

function $(id) {
  return document.getElementById(id);
}

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined && text !== null) node.textContent = text;
  return node;
}

function clear(node) {
  while (node.firstChild) node.removeChild(node.firstChild);
}

function levelClass(level) {
  switch ((level || "").toUpperCase()) {
    case "LOW": return "level low";
    case "MEDIUM": return "level medium";
    case "HIGH": return "level high";
    default: return "level unknown";
  }
}

function formatCoord(coord) {
  if (!coord) return "n/a";
  const lat = Number(coord.latitude).toFixed(5);
  const lon = Number(coord.longitude).toFixed(5);
  return `${lat}, ${lon}`;
}

function formatScore(score) {
  return score === null || score === undefined ? "—" : String(score);
}

// ---- Shared request handling ----------------------------------------------

async function postJson(url, payload) {
  const response = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });

  let body = null;
  try {
    body = await response.json();
  } catch (_e) {
    // No/invalid JSON body.
  }

  if (!response.ok) {
    // Error envelope: { code, message, field? }
    const message = body && body.message ? body.message : `Request failed (HTTP ${response.status}).`;
    const field = body && body.field ? body.field : null;
    const err = new Error(message);
    err.field = field;
    throw err;
  }
  return body;
}

function setStatus(node, kind, message) {
  node.className = "status" + (kind ? " " + kind : "");
  node.textContent = message || "";
}

function showError(statusNode, err) {
  const suffix = err.field ? ` (field: ${err.field})` : "";
  setStatus(statusNode, "error", err.message + suffix);
}

// ---- Leaflet map (Classify panel) -----------------------------------------

// Miami-Dade County default view.
const MAP_DEFAULT_CENTER = [25.77, -80.19];
const MAP_DEFAULT_ZOOM = 10;

let classifyMap = null;      // Leaflet map instance (lazy-initialized once).
let classifyRouteLayer = null; // Layer group holding the current polyline + markers.

function getClassifyMap() {
  if (classifyMap) return classifyMap;
  const container = $("classify-map");
  if (!container || typeof L === "undefined") return null;

  classifyMap = L.map(container).setView(MAP_DEFAULT_CENTER, MAP_DEFAULT_ZOOM);
  L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
    maxZoom: 19,
    attribution: "&copy; OpenStreetMap contributors",
  }).addTo(classifyMap);

  // The container may have been laid out after creation; correct tile layout.
  setTimeout(() => classifyMap.invalidateSize(), 0);
  return classifyMap;
}

// Build ordered [lat, lon] polyline points from route.segments:
// [seg0.start, seg0.end, seg1.end, seg2.end, ...].
function buildRoutePoints(route) {
  if (!route || !Array.isArray(route.segments) || route.segments.length === 0) {
    return [];
  }
  const points = [];
  const first = route.segments[0];
  if (first && first.start) {
    points.push([first.start.latitude, first.start.longitude]);
  }
  route.segments.forEach((seg) => {
    if (seg && seg.end) {
      points.push([seg.end.latitude, seg.end.longitude]);
    }
  });
  return points;
}

function coordToLatLng(coord) {
  if (!coord) return null;
  return [coord.latitude, coord.longitude];
}

function drawClassifyRoute(data) {
  const map = getClassifyMap();
  if (!map) return;

  // Remove any previously drawn route/markers.
  if (classifyRouteLayer) {
    map.removeLayer(classifyRouteLayer);
    classifyRouteLayer = null;
  }

  const group = L.layerGroup();
  const originLatLng = coordToLatLng(data.originCoordinate);
  const destLatLng = coordToLatLng(data.destinationCoordinate);

  if (originLatLng) {
    L.marker(originLatLng).bindPopup("Origin").addTo(group);
  }
  if (destLatLng) {
    L.marker(destLatLng).bindPopup("Destination").addTo(group);
  }

  const points = buildRoutePoints(data.route);
  let bounds = null;

  if (points.length >= 2) {
    const polyline = L.polyline(points, { color: "#15803d", weight: 5 });
    polyline.addTo(group);
    bounds = polyline.getBounds();
  } else {
    // Fallback: fit to origin + destination markers only.
    const fallback = [originLatLng, destLatLng].filter(Boolean);
    if (fallback.length > 0) {
      bounds = L.latLngBounds(fallback);
    }
  }

  group.addTo(map);
  classifyRouteLayer = group;

  if (bounds && bounds.isValid()) {
    map.fitBounds(bounds, { padding: [30, 30] });
  }

  // Ensure tiles lay out correctly now that the map is visible/updated.
  map.invalidateSize();
}

// ---- Leaflet map (Safest panel) -------------------------------------------

let safestMap = null;        // Separate Leaflet map instance for the safest panel.
let safestRouteLayer = null; // Layer group holding the current polyline + markers.
let safestData = null;       // Most recent safest API response.

function getSafestMap() {
  if (safestMap) return safestMap;
  const container = $("safest-map");
  if (!container || typeof L === "undefined") return null;

  safestMap = L.map(container).setView(MAP_DEFAULT_CENTER, MAP_DEFAULT_ZOOM);
  L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
    maxZoom: 19,
    attribution: "&copy; OpenStreetMap contributors",
  }).addTo(safestMap);

  // The container may have been laid out after creation; correct tile layout.
  setTimeout(() => safestMap.invalidateSize(), 0);
  return safestMap;
}

// Polyline color keyed by recommendation level.
function colorForLevel(level) {
  switch ((level || "").toUpperCase()) {
    case "LOW": return "#15803d";
    case "MEDIUM": return "#b45309";
    case "HIGH": return "#b91c1c";
    default: return "#64707f";
  }
}

// Draw one perCategory entry's recommended route onto the safest map.
function drawSafestRoute(recommendation) {
  const map = getSafestMap();
  if (!map) return;

  // Remove any previously drawn route/markers.
  if (safestRouteLayer) {
    map.removeLayer(safestRouteLayer);
    safestRouteLayer = null;
  }

  const route = recommendation && recommendation.recommendedRoute;
  const points = buildRoutePoints(route);
  if (points.length === 0) {
    // No scorable route for this category: leave the map empty.
    map.invalidateSize();
    return;
  }

  const group = L.layerGroup();
  const category = (recommendation && recommendation.category) || "";

  const polyline = L.polyline(points, {
    color: colorForLevel(recommendation && recommendation.level),
    weight: 5,
  });
  polyline.addTo(group);

  const startPoint = points[0];
  const endPoint = points[points.length - 1];
  L.marker(startPoint).bindPopup(category ? category + " start" : "Start").addTo(group);
  L.marker(endPoint).bindPopup(category ? category + " end" : "End").addTo(group);

  group.addTo(map);
  safestRouteLayer = group;

  const bounds = polyline.getBounds();
  if (bounds && bounds.isValid()) {
    map.fitBounds(bounds, { padding: [30, 30] });
  }
  map.invalidateSize();
}

// ---- Panel A: Classify a route --------------------------------------------

function renderClassifyResult(container, data) {
  clear(container);

  // Resolved coordinates.
  const coordCard = el("div", "card");
  coordCard.appendChild(el("h3", null, "Resolved coordinates"));
  const originLine = el("div", "coords");
  originLine.textContent = `Origin: ${formatCoord(data.originCoordinate)}`;
  const destLine = el("div", "coords");
  destLine.textContent = `Destination: ${formatCoord(data.destinationCoordinate)}`;
  coordCard.appendChild(originLine);
  coordCard.appendChild(destLine);
  if (data.route && data.route.totalDistanceMeters !== undefined) {
    const dist = el("div", "muted");
    dist.textContent = `Route distance: ${(data.route.totalDistanceMeters / 1000).toFixed(2)} km`;
    coordCard.appendChild(dist);
  }
  container.appendChild(coordCard);

  // Category assessments.
  const assessments = (data.classification && data.classification.assessments) || [];
  const riskCard = el("div", "card");
  riskCard.appendChild(el("h3", null, "Risk by category"));
  assessments.forEach((a) => {
    const row = el("div", "assessment");
    row.appendChild(el("span", "category-name", a.category));
    const right = el("span");
    right.appendChild(el("span", "score", `score ${formatScore(a.score)}`));
    right.appendChild(document.createTextNode(" "));
    right.appendChild(el("span", levelClass(a.level), a.level));
    row.appendChild(right);
    riskCard.appendChild(row);
  });
  container.appendChild(riskCard);

  // Insurance recommendations.
  const rec = data.recommendation || {};
  const recs = rec.recommendations || [];
  const recCard = el("div", "card");
  recCard.appendChild(el("h3", null, "Insurance recommendations"));
  if (rec.status) {
    recCard.appendChild(el("div", "muted", `Status: ${rec.status}`));
  }
  if (recs.length === 0) {
    recCard.appendChild(el("div", "muted", "No specific coverage recommended."));
  } else {
    recs.forEach((r) => {
      const item = el("div", "recommendation-item");
      item.appendChild(el("span", "insurance-type", r.insuranceType));
      const just = r.justifiedBy || [];
      const justWrap = el("div");
      just.forEach((j) => {
        justWrap.appendChild(el("span", "chip", `${j.category}: ${j.level}`));
      });
      item.appendChild(justWrap);
      recCard.appendChild(item);
    });
  }
  container.appendChild(recCard);
}

function initClassifyPanel() {
  const form = $("classify-form");
  const statusNode = $("classify-status");
  const resultNode = $("classify-result");
  const submitBtn = form.querySelector("button[type=submit]");

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    clear(resultNode);
    const origin = $("classify-origin").value.trim();
    const destination = $("classify-destination").value.trim();

    submitBtn.disabled = true;
    setStatus(statusNode, "loading", "Classifying route…");
    try {
      const data = await postJson("/api/routes/classify", { origin, destination });
      setStatus(statusNode, "", "");
      renderClassifyResult(resultNode, data);
      drawClassifyRoute(data);
    } catch (err) {
      showError(statusNode, err);
    } finally {
      submitBtn.disabled = false;
    }
  });
}

// ---- Panel B: Find the safest route ---------------------------------------

const MIN_LOCATIONS = 2;
const MAX_LOCATIONS = 25;

function updateLocationCount() {
  const list = $("locations-list");
  const rows = list.querySelectorAll(".location-row");
  const count = rows.length;
  $("location-count").textContent = `${count} of ${MAX_LOCATIONS} locations`;
  $("add-location").disabled = count >= MAX_LOCATIONS;
  // Enable remove buttons only when above the minimum.
  rows.forEach((row) => {
    const removeBtn = row.querySelector("button.remove");
    if (removeBtn) removeBtn.disabled = count <= MIN_LOCATIONS;
  });
}

function addLocationRow(value) {
  const list = $("locations-list");
  const row = el("div", "location-row");

  const input = el("input");
  input.type = "text";
  input.placeholder = "e.g. Miami Beach";
  if (value) input.value = value;

  const removeBtn = el("button", "remove", "Remove");
  removeBtn.type = "button";
  removeBtn.addEventListener("click", () => {
    row.remove();
    updateLocationCount();
  });

  row.appendChild(input);
  row.appendChild(removeBtn);
  list.appendChild(row);
  updateLocationCount();
}

function renderSafestResult(container, data) {
  clear(container);
  const perCategory = (data && data.perCategory) || [];
  const card = el("div", "card");
  card.appendChild(el("h3", null, "Safest route per category"));

  if (perCategory.length === 0) {
    card.appendChild(el("div", "muted", "No per-category result returned."));
  } else {
    perCategory.forEach((c) => {
      const row = el("div", "per-category-row");
      const left = el("span");
      left.appendChild(el("span", "category-name", c.category));
      const route = c.recommendedRoute;
      const routeLabel = route
        ? ` — ${route.id} (${(route.totalDistanceMeters / 1000).toFixed(2)} km)`
        : " — no scorable route";
      left.appendChild(el("span", "muted", routeLabel));
      row.appendChild(left);

      const right = el("span");
      right.appendChild(el("span", "score", `score ${formatScore(c.score)}`));
      right.appendChild(document.createTextNode(" "));
      right.appendChild(el("span", levelClass(c.level), c.level));
      row.appendChild(right);
      card.appendChild(row);
    });
  }
  container.appendChild(card);
}

// Build the per-category toggle row and auto-select the first scorable route.
function renderSafestToggle(container, data) {
  clear(container);
  const perCategory = (data && data.perCategory) || [];
  if (perCategory.length === 0) {
    getSafestMap(); // Ensure a default map view is present.
    return;
  }

  const buttons = [];
  let firstScorableIndex = -1;

  perCategory.forEach((entry, index) => {
    const hasRoute = !!(entry.recommendedRoute && Array.isArray(entry.recommendedRoute.segments) && entry.recommendedRoute.segments.length > 0);
    if (hasRoute && firstScorableIndex === -1) firstScorableIndex = index;

    const btn = el("button", null, `${entry.category} · ${entry.level}`);
    btn.type = "button";
    if (!hasRoute) {
      btn.disabled = true;
      btn.title = "no scorable route";
    } else {
      btn.addEventListener("click", () => {
        buttons.forEach((b) => b.classList.remove("active"));
        btn.classList.add("active");
        drawSafestRoute(entry);
      });
    }
    buttons.push(btn);
    container.appendChild(btn);
  });

  if (firstScorableIndex >= 0) {
    buttons[firstScorableIndex].classList.add("active");
    drawSafestRoute(perCategory[firstScorableIndex]);
  } else {
    // No category has a scorable route: show the default Miami-Dade view.
    const map = getSafestMap();
    if (map) {
      if (safestRouteLayer) {
        map.removeLayer(safestRouteLayer);
        safestRouteLayer = null;
      }
      map.setView(MAP_DEFAULT_CENTER, MAP_DEFAULT_ZOOM);
      map.invalidateSize();
    }
    container.appendChild(el("div", "muted", "No scorable route to display on the map."));
  }
}

function initSafestPanel() {
  const form = $("safest-form");
  const statusNode = $("safest-status");
  const resultNode = $("safest-result");
  const submitBtn = form.querySelector("button[type=submit]");

  // Seed with the minimum number of location rows.
  addLocationRow("");
  addLocationRow("");

  $("add-location").addEventListener("click", () => addLocationRow(""));

  // Container for the per-category recommendation toggle, placed above the map.
  const toggleNode = el("div", "toggle-group");
  toggleNode.id = "safest-toggle";
  resultNode.parentNode.insertBefore(toggleNode, $("safest-map"));

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    clear(resultNode);
    clear(toggleNode);

    const inputs = $("locations-list").querySelectorAll("input[type=text]");
    const locations = Array.from(inputs)
      .map((i) => i.value.trim())
      .filter((v) => v.length > 0);

    if (locations.length < MIN_LOCATIONS) {
      setStatus(statusNode, "error", `Please provide at least ${MIN_LOCATIONS} locations.`);
      return;
    }

    submitBtn.disabled = true;
    setStatus(statusNode, "loading", "Comparing candidate routes…");
    try {
      const data = await postJson("/api/routes/safest", { locations });
      setStatus(statusNode, "", "");
      safestData = data;
      renderSafestResult(resultNode, data);
      renderSafestToggle(toggleNode, data);
    } catch (err) {
      showError(statusNode, err);
    } finally {
      submitBtn.disabled = false;
    }
  });
}

// ---- Bootstrap -------------------------------------------------------------

document.addEventListener("DOMContentLoaded", () => {
  initClassifyPanel();
  initSafestPanel();
});
