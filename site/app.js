const state = { addons: [], q: "", type: "" };

const cards = document.getElementById("cards");
const status = document.getElementById("status");
const template = document.getElementById("card");

function size(bytes) {
  const mb = bytes / 1048576;
  return mb >= 1 ? mb.toFixed(1) + " MB" : Math.round(bytes / 1024) + " KB";
}

// Only http(s) and same-origin relative URLs may reach an href or src.
// Every catalog URL is derived by the generator today, but the page renders
// data fetched over the network, and "javascript:" in an href would run.
function safeUrl(value) {
  if (typeof value !== "string") return "";
  try {
    const url = new URL(value, location.origin);
    return url.protocol === "http:" || url.protocol === "https:" ? url.href : "";
  } catch {
    return "";
  }
}

function matches(addon) {
  if (state.type && addon.type !== state.type) return false;
  if (!state.q) return true;
  const text = [addon.name, addon.summary, addon.description, ...addon.tags]
    .join(" ").toLowerCase();
  return state.q.toLowerCase().split(/\s+/).every((word) => text.includes(word));
}

function render() {
  const shown = state.addons.filter(matches);
  cards.replaceChildren();
  for (const addon of shown) {
    const node = template.content.cloneNode(true);
    const set = (slot, value) => {
      node.querySelector(`[data-slot="${slot}"]`).textContent = value;
    };
    set("name", addon.name);
    set("type", addon.type);
    set("summary", addon.summary);
    set("version", "v" + addon.version);
    set("origin", addon.origin === "community" ? "Community" : "App Dev For All");
    set("size", size(addon.download.size));
    node.querySelector(".icon").src = safeUrl(addon.iconUrl);
    node.querySelector('[data-slot="download"]').href = safeUrl(addon.download.url);
    node.querySelector('[data-slot="page"]').href = safeUrl(addon.pageUrl);
    node.querySelector('[data-slot="source"]').href = safeUrl(addon.sourceUrl);
    cards.append(node);
  }
  status.textContent = shown.length ? "" : "No addon matches this search.";
  const url = new URL(location.href);
  url.search = new URLSearchParams(
    Object.entries({ q: state.q, type: state.type }).filter(([, v]) => v)
  ).toString();
  history.replaceState(null, "", url);
}

document.getElementById("q").addEventListener("input", (event) => {
  state.q = event.target.value;
  render();
});
document.getElementById("type").addEventListener("change", (event) => {
  state.type = event.target.value;
  render();
});

const params = new URLSearchParams(location.search);
state.q = params.get("q") || "";
state.type = params.get("type") || "";
document.getElementById("q").value = state.q;
document.getElementById("type").value = state.type;

fetch("/v1/catalog.json")
  .then((response) => {
    if (!response.ok) throw new Error(response.status);
    return response.json();
  })
  .then((document_) => {
    state.addons = document_.addons;
    render();
  })
  .catch(() => {
    status.textContent = "The addon list did not load. Please try again later.";
  });
