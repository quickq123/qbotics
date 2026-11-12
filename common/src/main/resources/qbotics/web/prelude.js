// The browser a web panel's scripts see (design doc §10.3): a small DOM, timers, <canvas>
// drawing, and the panel. Runs in QuickJS in the player's game, before the page's scripts. The
// game reaches it through __qb; it reaches the game through __host(op, text).
(function () {
  "use strict";
  const host = globalThis.__host;
  const VOID = new Set(["area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr"]);
  const RAW = new Set(["script", "style", "textarea", "title"]);
  const byNumber = new Map();
  let nextNumber = 1;
  let domChanged = true;
  function changed() { domChanged = true; }

  // ---- entities, escaping ----------------------------------------------------------------
  const NAMED = { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'", nbsp: " ", copy: "©", times: "×", middot: "·", hellip: "…", mdash: "—", ndash: "–", deg: "°" };
  function decode(text) {
    return text.replace(/&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);/g, (m, e) => {
      if (e[0] === "#") {
        const code = e[1] === "x" || e[1] === "X" ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10);
        return isNaN(code) ? m : String.fromCodePoint(code);
      }
      return NAMED[e] !== undefined ? NAMED[e] : m;
    });
  }
  function escapeText(s) { return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;"); }
  function escapeAttr(s) { return escapeText(s).replace(/"/g, "&quot;"); }

  // ---- nodes -------------------------------------------------------------------------------
  class Node {
    constructor(type) { this.nodeType = type; this.parentNode = null; this.childNodes = []; }
    get parentElement() { return this.parentNode && this.parentNode.nodeType === 1 ? this.parentNode : null; }
    get firstChild() { return this.childNodes[0] || null; }
    get lastChild() { return this.childNodes[this.childNodes.length - 1] || null; }
    get nextSibling() { const p = this.parentNode; if (!p) return null; const i = p.childNodes.indexOf(this); return p.childNodes[i + 1] || null; }
    get previousSibling() { const p = this.parentNode; if (!p) return null; const i = p.childNodes.indexOf(this); return i > 0 ? p.childNodes[i - 1] : null; }
    appendChild(node) { return this.insertBefore(node, null); }
    insertBefore(node, ref) {
      if (node.nodeType === 11) { for (const c of node.childNodes.slice()) this.insertBefore(c, ref); return node; }
      if (node.parentNode) node.parentNode.removeChild(node);
      const i = ref ? this.childNodes.indexOf(ref) : -1;
      if (i < 0) this.childNodes.push(node); else this.childNodes.splice(i, 0, node);
      node.parentNode = this;
      changed();
      return node;
    }
    removeChild(node) {
      const i = this.childNodes.indexOf(node);
      if (i >= 0) { this.childNodes.splice(i, 1); node.parentNode = null; changed(); }
      return node;
    }
    replaceChild(node, old) { this.insertBefore(node, old); return this.removeChild(old); }
    remove() { if (this.parentNode) this.parentNode.removeChild(this); }
    append(...nodes) { for (const n of nodes) this.appendChild(typeof n === "string" ? new Text(n) : n); }
    prepend(...nodes) { const first = this.firstChild; for (const n of nodes) this.insertBefore(typeof n === "string" ? new Text(n) : n, first); }
    replaceChildren(...nodes) { for (const c of this.childNodes) c.parentNode = null; this.childNodes = []; changed(); this.append(...nodes); }
    contains(node) { for (let n = node; n; n = n.parentNode) if (n === this) return true; return false; }
    hasChildNodes() { return this.childNodes.length > 0; }
    get textContent() { return this.childNodes.map(c => c.nodeType === 8 ? "" : c.textContent).join(""); }
    set textContent(v) { for (const c of this.childNodes) c.parentNode = null; this.childNodes = []; changed(); if (v !== null && v !== undefined && v !== "") this.appendChild(new Text(String(v))); }
  }
  class Text extends Node {
    constructor(data) { super(3); this.data = String(data); }
    get textContent() { return this.data; }
    set textContent(v) { this.data = String(v); changed(); }
    get nodeValue() { return this.data; }
    set nodeValue(v) { this.textContent = v; }
    get nodeName() { return "#text"; }
  }
  class Comment extends Node {
    constructor(data) { super(8); this.data = String(data); }
    get nodeName() { return "#comment"; }
  }
  class Fragment extends Node {
    constructor() { super(11); }
    querySelector(s) { return first(this, s); }
    querySelectorAll(s) { return all(this, s); }
  }

  function camelToKebab(name) { return name.replace(/[A-Z]/g, c => "-" + c.toLowerCase()); }
  function makeStyle(el) {
    const props = new Map();
    const style = {
      get cssText() { return [...props].map(([k, v]) => k + ": " + v).join("; "); },
      set cssText(text) {
        props.clear();
        for (const part of String(text).split(";")) {
          const i = part.indexOf(":");
          if (i > 0) props.set(part.slice(0, i).trim().toLowerCase(), part.slice(i + 1).trim());
        }
        changed();
      },
      setProperty(k, v) { if (v === null || v === "") props.delete(k); else props.set(k, String(v)); changed(); },
      getPropertyValue(k) { return props.get(k) || ""; },
      removeProperty(k) { const v = props.get(k) || ""; props.delete(k); changed(); return v; },
    };
    return new Proxy(style, {
      get(t, k) { if (k in t) return t[k]; return typeof k === "string" ? (props.get(camelToKebab(k)) || "") : undefined; },
      set(t, k, v) { if (k === "cssText") { t.cssText = v; return true; } t.setProperty(camelToKebab(String(k)), v === null || v === undefined ? "" : String(v)); return true; },
    });
  }
  function makeClassList(el) {
    const get = () => (el.getAttribute("class") || "").split(/\s+/).filter(Boolean);
    const set = list => el.setAttribute("class", list.join(" "));
    return {
      add(...names) { const l = get(); for (const n of names) if (!l.includes(n)) l.push(n); set(l); },
      remove(...names) { set(get().filter(c => !names.includes(c))); },
      toggle(name, force) { const has = get().includes(name); const want = force === undefined ? !has : !!force; if (want && !has) this.add(name); if (!want && has) this.remove(name); return want; },
      contains(name) { return get().includes(name); },
      replace(a, b) { set(get().map(c => c === a ? b : c)); },
      get length() { return get().length; },
      item(i) { return get()[i] || null; },
      toString() { return get().join(" "); },
      [Symbol.iterator]() { return get()[Symbol.iterator](); },
    };
  }

  class Element extends Node {
    constructor(tag) {
      super(1);
      this.localName = String(tag).toLowerCase();
      this.attrs = new Map();
      this.number = nextNumber++;
      byNumber.set(this.number, this);
      this.style = makeStyle(this);
      this.classList = makeClassList(this);
      this.listeners = {};
    }
    get tagName() { return this.localName.toUpperCase(); }
    get nodeName() { return this.tagName; }
    getAttribute(name) {
      name = String(name).toLowerCase();
      if (name === "style") { const s = this.style.cssText; return s === "" ? null : s; }
      return this.attrs.has(name) ? this.attrs.get(name) : null;
    }
    setAttribute(name, value) {
      name = String(name).toLowerCase();
      if (name === "style") this.style.cssText = value; else { this.attrs.set(name, String(value)); changed(); }
    }
    removeAttribute(name) { name = String(name).toLowerCase(); if (name === "style") this.style.cssText = ""; else if (this.attrs.delete(name)) changed(); }
    hasAttribute(name) { return this.getAttribute(name) !== null; }
    toggleAttribute(name, force) { const want = force === undefined ? !this.hasAttribute(name) : !!force; if (want) this.setAttribute(name, ""); else this.removeAttribute(name); return want; }
    getAttributeNames() { const names = [...this.attrs.keys()]; if (this.style.cssText) names.push("style"); return names; }
    get id() { return this.getAttribute("id") || ""; }
    set id(v) { this.setAttribute("id", v); }
    get className() { return this.getAttribute("class") || ""; }
    set className(v) { this.setAttribute("class", v); }
    get hidden() { return this.hasAttribute("hidden"); }
    set hidden(v) { this.toggleAttribute("hidden", !!v); }
    get title() { return this.getAttribute("title") || ""; }
    set title(v) { this.setAttribute("title", v); }
    get src() { return this.getAttribute("src") || ""; }
    set src(v) { this.setAttribute("src", v); }
    get href() { return this.getAttribute("href") || ""; }
    set href(v) { this.setAttribute("href", v); }
    get disabled() { return this.hasAttribute("disabled"); }
    set disabled(v) { this.toggleAttribute("disabled", !!v); }
    get value() {
      const field = this.getAttribute("name") || this.id || "input";
      if (this.localName === "select") {
        const typed = host("value", field);
        if (typed !== undefined) return typed;
        const options = this.getElementsByTagName("option");
        const chosen = options.find(o => o.hasAttribute("selected")) || options[0];
        return chosen ? (chosen.getAttribute("value") ?? chosen.textContent.trim()) : "";
      }
      if (this.localName === "input" || this.localName === "textarea") {
        const type = (this.getAttribute("type") || "text").toLowerCase();
        if (type === "checkbox" || type === "radio") return this.getAttribute("value") || "on";
        const typed = host("value", field);
        if (typed !== undefined) return typed;
        return this.localName === "textarea" ? this.textContent : (this.getAttribute("value") || "");
      }
      return this.getAttribute("value") || "";
    }
    // A checkbox's or radio button's state, as the player left it.
    get checked() {
      const field = this.getAttribute("name") || this.id || "input";
      const typed = host("value", field);
      if (typed === undefined) return this.hasAttribute("checked");
      return (this.getAttribute("type") || "").toLowerCase() === "radio" ? typed === (this.getAttribute("value") || "on") : typed !== "";
    }
    set checked(v) {
      const field = this.getAttribute("name") || this.id || "input";
      const radio = (this.getAttribute("type") || "").toLowerCase() === "radio";
      host("set_value", JSON.stringify([field, v ? (this.getAttribute("value") || "on") : (radio ? "" : "")]));
      changed();
    }
    set value(v) {
      this.setAttribute("value", v);
      if (this.localName === "input") host("set_value", JSON.stringify([this.getAttribute("name") || this.id || "input", String(v)]));
    }
    get dataset() {
      const el = this;
      return new Proxy({}, {
        get(t, k) { return typeof k === "string" ? (el.getAttribute("data-" + camelToKebab(k)) ?? undefined) : undefined; },
        set(t, k, v) { el.setAttribute("data-" + camelToKebab(String(k)), v); return true; },
        deleteProperty(t, k) { el.removeAttribute("data-" + camelToKebab(String(k))); return true; },
      });
    }
    get children() { return this.childNodes.filter(c => c.nodeType === 1); }
    get childElementCount() { return this.children.length; }
    get firstElementChild() { return this.children[0] || null; }
    get lastElementChild() { const c = this.children; return c[c.length - 1] || null; }
    get nextElementSibling() { for (let n = this.nextSibling; n; n = n.nextSibling) if (n.nodeType === 1) return n; return null; }
    get previousElementSibling() { for (let n = this.previousSibling; n; n = n.previousSibling) if (n.nodeType === 1) return n; return null; }
    get innerHTML() { return this.childNodes.map(c => serialize(c, false)).join(""); }
    set innerHTML(html) { for (const c of this.childNodes) c.parentNode = null; this.childNodes = []; changed(); for (const n of parse(String(html))) this.appendChild(n); }
    get outerHTML() { return serialize(this, false); }
    get innerText() { return this.textContent; }
    set innerText(v) { this.textContent = v; }
    insertAdjacentHTML(where, html) {
      const nodes = parse(String(html));
      const w = String(where).toLowerCase();
      for (const n of (w === "afterbegin" || w === "afterend") ? nodes.reverse() : nodes) {
        if (w === "beforebegin") this.parentNode && this.parentNode.insertBefore(n, this);
        else if (w === "afterbegin") this.insertBefore(n, this.firstChild);
        else if (w === "beforeend") this.appendChild(n);
        else if (w === "afterend") this.parentNode && this.parentNode.insertBefore(n, this.nextSibling);
      }
    }
    addEventListener(type, fn) { if (typeof fn === "function") (this.listeners[type] = this.listeners[type] || []).push(fn); }
    removeEventListener(type, fn) { const l = this.listeners[type]; if (l) { const i = l.indexOf(fn); if (i >= 0) l.splice(i, 1); } }
    dispatchEvent(event) { return dispatch(this, event); }
    click() { dispatch(this, makeEvent("click", {})); }
    focus() {}
    blur() {}
    matches(selector) { return parseSelector(selector).some(s => matchComplex(this, s)); }
    closest(selector) { for (let n = this; n && n.nodeType === 1; n = n.parentNode) if (n.matches(selector)) return n; return null; }
    querySelector(s) { return first(this, s); }
    querySelectorAll(s) { return all(this, s); }
    getElementsByTagName(tag) { tag = tag.toLowerCase(); return descendants(this).filter(e => tag === "*" || e.localName === tag); }
    getElementsByClassName(name) { return descendants(this).filter(e => e.classList.contains(name)); }
    getBoundingClientRect() { return { x: 0, y: 0, left: 0, top: 0, width: 0, height: 0, right: 0, bottom: 0 }; }
  }

  function descendants(root) {
    const out = [];
    (function walk(n) { for (const c of n.childNodes) if (c.nodeType === 1) { out.push(c); walk(c); } })(root);
    return out;
  }

  // ---- selectors: tag, #id, .class, [attr], [attr=value], *, :first-child, :last-child, descendant and > ----
  function parseSelector(text) {
    return String(text).split(",").map(group => {
      const parts = [];
      const re = /\s*(>)?\s*([^\s>]+)/g;
      let m;
      while ((m = re.exec(group.trim())) !== null) parts.push({ child: !!m[1], compound: parseCompound(m[2]) });
      return parts;
    }).filter(p => p.length);
  }
  function parseCompound(text) {
    const c = { tag: null, id: null, classes: [], attrs: [], pseudo: [] };
    const re = /([a-zA-Z][\w-]*|\*)|#([\w-]+)|\.([\w-]+)|\[([\w-]+)(?:([~^$*|]?=)"?([^"\]]*)"?)?\]|:([\w-]+)/g;
    let m;
    while ((m = re.exec(text)) !== null) {
      if (m[1]) c.tag = m[1] === "*" ? null : m[1].toLowerCase();
      else if (m[2]) c.id = m[2];
      else if (m[3]) c.classes.push(m[3]);
      else if (m[4]) c.attrs.push([m[4].toLowerCase(), m[5], m[6]]);
      else if (m[7]) c.pseudo.push(m[7]);
    }
    return c;
  }
  function matchCompound(el, c) {
    if (c.tag && el.localName !== c.tag) return false;
    if (c.id && el.id !== c.id) return false;
    for (const name of c.classes) if (!el.classList.contains(name)) return false;
    for (const [name, op, value] of c.attrs) {
      const v = el.getAttribute(name);
      if (v === null) return false;
      if (op === "=" && v !== value) return false;
      if (op === "~=" && !v.split(/\s+/).includes(value)) return false;
      if (op === "^=" && !v.startsWith(value)) return false;
      if (op === "$=" && !v.endsWith(value)) return false;
      if (op === "*=" && !v.includes(value)) return false;
    }
    for (const p of c.pseudo) {
      const siblings = el.parentNode ? el.parentNode.children : [el];
      if (p === "first-child" && siblings[0] !== el) return false;
      if (p === "last-child" && siblings[siblings.length - 1] !== el) return false;
    }
    return true;
  }
  function matchComplex(el, parts, i = parts.length - 1) {
    if (!matchCompound(el, parts[i].compound)) return false;
    if (i === 0) return true;
    if (parts[i].child) return !!el.parentNode && el.parentNode.nodeType === 1 && matchComplex(el.parentNode, parts, i - 1);
    for (let p = el.parentNode; p && p.nodeType === 1; p = p.parentNode) if (matchComplex(p, parts, i - 1)) return true;
    return false;
  }
  function all(root, selector) {
    const groups = parseSelector(selector);
    return descendants(root).filter(e => groups.some(g => matchComplex(e, g)));
  }
  function first(root, selector) { return all(root, selector)[0] || null; }

  // ---- parsing and writing HTML -------------------------------------------------------------
  function parseAttrs(text) {
    const out = [];
    const re = /([^\s=\/>"']+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+)))?/g;
    let m;
    while ((m = re.exec(text)) !== null) out.push([m[1].toLowerCase(), decode(m[2] ?? m[3] ?? m[4] ?? "")]);
    return out;
  }
  function parse(html) {
    const root = new Fragment();
    const stack = [root];
    let i = 0;
    const top = () => stack[stack.length - 1];
    while (i < html.length) {
      const lt = html.indexOf("<", i);
      if (lt < 0) { top().appendChild(new Text(decode(html.slice(i)))); break; }
      if (lt > i) top().appendChild(new Text(decode(html.slice(i, lt))));
      if (html.startsWith("<!--", lt)) {
        const end = html.indexOf("-->", lt + 4);
        top().appendChild(new Comment(html.slice(lt + 4, end < 0 ? html.length : end)));
        i = end < 0 ? html.length : end + 3;
        continue;
      }
      if (html[lt + 1] === "!" || html[lt + 1] === "?") { const end = html.indexOf(">", lt); i = end < 0 ? html.length : end + 1; continue; }
      if (html[lt + 1] === "/") {
        const end = html.indexOf(">", lt);
        const name = html.slice(lt + 2, end < 0 ? html.length : end).trim().toLowerCase();
        for (let s = stack.length - 1; s > 0; s--) if (stack[s].localName === name) { stack.length = s; break; }
        i = end < 0 ? html.length : end + 1;
        continue;
      }
      const m = /^<([a-zA-Z][\w-]*)((?:[^>"']|"[^"]*"|'[^']*')*)>/.exec(html.slice(lt));
      if (!m) { top().appendChild(new Text("<")); i = lt + 1; continue; }
      const name = m[1].toLowerCase();
      const el = name === "canvas" ? new Canvas() : new Element(name);
      for (const [k, v] of parseAttrs(m[2].replace(/\/\s*$/, ""))) el.setAttribute(k, v);
      top().appendChild(el);
      i = lt + m[0].length;
      if (RAW.has(name)) {
        const close = html.toLowerCase().indexOf("</" + name, i);
        const text = html.slice(i, close < 0 ? html.length : close);
        if (text) el.appendChild(new Text(name === "textarea" || name === "title" ? decode(text) : text));
        const end = close < 0 ? html.length : html.indexOf(">", close);
        i = end < 0 ? html.length : end + 1;
      } else if (!VOID.has(name) && !/\/\s*>$/.test(m[0])) {
        stack.push(el);
      }
    }
    return root.childNodes.slice();
  }
  function serialize(node, forLayout) {
    if (node.nodeType === 3) return node.parentNode && RAW.has(node.parentNode.localName) ? node.data : escapeText(node.data);
    if (node.nodeType === 8) return forLayout ? "" : "<!--" + node.data + "-->";
    if (node.nodeType !== 1) return node.childNodes.map(c => serialize(c, forLayout)).join("");
    const tag = node.localName;
    if (forLayout && tag === "script") return "";
    let attrs = "";
    for (const name of node.getAttributeNames()) attrs += " " + name + '="' + escapeAttr(node.getAttribute(name)) + '"';
    if (forLayout) {
      attrs += ' data-qbn="' + node.number + '"';
      if (tag === "canvas") return '<img src="canvas:' + node.number + '" width="' + node.width + '" height="' + node.height + '"' + attrs + ">";
    }
    if (VOID.has(tag)) return "<" + tag + attrs + ">";
    return "<" + tag + attrs + ">" + node.childNodes.map(c => serialize(c, forLayout)).join("") + "</" + tag + ">";
  }

  // ---- events ---------------------------------------------------------------------------------
  function makeEvent(type, init) {
    const e = Object.assign({ type, bubbles: true, defaultPrevented: false, stopped: false, target: null, currentTarget: null, timeStamp: Date.now() }, init);
    e.preventDefault = () => { e.defaultPrevented = true; };
    e.stopPropagation = () => { e.stopped = true; };
    e.stopImmediatePropagation = () => { e.stopped = true; };
    return e;
  }
  function runListeners(node, event) {
    const list = node.listeners && node.listeners[event.type];
    if (list) for (const fn of list.slice()) { event.currentTarget = node; fn.call(node, event); }
    const handler = node.attrs && node.attrs.get("on" + event.type);
    if (handler) { event.currentTarget = node; (new Function("event", handler)).call(node, event); }
  }
  function dispatch(target, event) {
    event.target = target;
    for (let n = target; n && !event.stopped; n = n.parentNode) {
      runListeners(n, event);
      if (!event.bubbles) break;
      if (n === document.documentElement) { runListeners(document, event); if (!event.stopped) runListeners(window, event); }
    }
    return !event.defaultPrevented;
  }

  // ---- <canvas> ---------------------------------------------------------------------------------
  const canvases = new Map();
  class Canvas extends Element {
    constructor() { super("canvas"); this.ops = []; this.full = true; canvases.set(this.number, this); }
    get width() { return parseInt(this.getAttribute("width") || "300", 10); }
    set width(v) { this.setAttribute("width", String(v | 0)); this.ops = []; this.full = true; }
    get height() { return parseInt(this.getAttribute("height") || "150", 10); }
    set height(v) { this.setAttribute("height", String(v | 0)); this.ops = []; this.full = true; }
    getContext(kind) {
      if (kind !== "2d") return null;
      if (!this.context) this.context = makeContext(this);
      return this.context;
    }
  }
  function makeContext(canvas) {
    const op = (...a) => { canvas.ops.push(a); };
    const state = { fillStyle: "#000000", strokeStyle: "#000000", lineWidth: 1, font: "10px sans-serif", textAlign: "start", textBaseline: "alphabetic", globalAlpha: 1, lineCap: "butt", lineJoin: "miter" };
    const saved = [];
    const ctx = {
      canvas,
      save() { saved.push(Object.assign({}, state)); op("save"); },
      restore() { if (saved.length) Object.assign(state, saved.pop()); op("restore"); },
      translate(x, y) { op("translate", +x, +y); },
      rotate(a) { op("rotate", +a); },
      scale(x, y) { op("scale", +x, +y); },
      setTransform(a, b, c, d, e, f) { op("transform", +a, +b, +c, +d, +e, +f); },
      resetTransform() { op("transform", 1, 0, 0, 1, 0, 0); },
      clearRect(x, y, w, h) { op("clear", +x, +y, +w, +h); },
      fillRect(x, y, w, h) { op("fillRect", +x, +y, +w, +h); },
      strokeRect(x, y, w, h) { op("strokeRect", +x, +y, +w, +h); },
      beginPath() { op("begin"); },
      closePath() { op("close"); },
      moveTo(x, y) { op("move", +x, +y); },
      lineTo(x, y) { op("line", +x, +y); },
      rect(x, y, w, h) { op("rect", +x, +y, +w, +h); },
      roundRect(x, y, w, h, r) { op("roundRect", +x, +y, +w, +h, +(Array.isArray(r) ? r[0] : r || 0)); },
      arc(x, y, r, a0, a1, ccw) { op("arc", +x, +y, +r, +a0, +a1, !!ccw); },
      ellipse(x, y, rx, ry, rot, a0, a1, ccw) { op("ellipse", +x, +y, +rx, +ry, +rot, +a0, +a1, !!ccw); },
      quadraticCurveTo(cx, cy, x, y) { op("quad", +cx, +cy, +x, +y); },
      bezierCurveTo(c1x, c1y, c2x, c2y, x, y) { op("bezier", +c1x, +c1y, +c2x, +c2y, +x, +y); },
      fill() { op("fill"); },
      stroke() { op("stroke"); },
      clip() { op("clip"); },
      fillText(t, x, y) { op("fillText", String(t), +x, +y); },
      strokeText(t, x, y) { op("strokeText", String(t), +x, +y); },
      measureText(t) { return { width: +host("measure", JSON.stringify([state.font, String(t)])) }; },
      createLinearGradient(x0, y0, x1, y1) { return { kind: "linear", at: [+x0, +y0, +x1, +y1], stops: [], addColorStop(o, c) { this.stops.push([+o, String(c)]); } }; },
      createRadialGradient(x0, y0, r0, x1, y1, r1) { return { kind: "radial", at: [+x1, +y1, +r1], stops: [], addColorStop(o, c) { this.stops.push([+o, String(c)]); } }; },
      // A canvas, an <img> or an Image: item:minecraft:diamond, image:name (a Qbot's picture), or canvas.
      drawImage(image, ...a) {
        if (!image) return;
        if (image.localName === "canvas" && image.number) { op("drawCanvas", image.number, ...a.map(Number)); return; }
        const src = image.src || (image.getAttribute ? image.getAttribute("src") : "");
        if (src) op("drawUrl", String(src), ...a.map(Number));
      },
      imageSmoothingEnabled: false,
      setLineDash(d) { op("dash", (d || []).map(Number)); },
    };
    for (const name of Object.keys(state)) {
      Object.defineProperty(ctx, name, {
        get() { return state[name]; },
        set(v) {
          state[name] = v;
          if (v && typeof v === "object" && v.stops) op(name, { kind: v.kind, at: v.at, stops: v.stops });
          else op(name, typeof v === "number" ? v : String(v));
        },
      });
    }
    return ctx;
  }

  // ---- the document and the window ---------------------------------------------------------------
  const documentElement = new Element("html");
  const document = {
    nodeType: 9,
    documentElement,
    listeners: {},
    get head() { return documentElement.children.find(e => e.localName === "head") || null; },
    get body() { return documentElement.children.find(e => e.localName === "body") || documentElement; },
    get title() { const t = first(documentElement, "title"); return t ? t.textContent : ""; },
    createElement(tag) { return String(tag).toLowerCase() === "canvas" ? new Canvas() : new Element(tag); },
    createTextNode(text) { return new Text(text); },
    createComment(text) { return new Comment(text); },
    createDocumentFragment() { return new Fragment(); },
    getElementById(id) { return descendants(documentElement).find(e => e.id === String(id)) || null; },
    querySelector(s) { return first(documentElement, s); },
    querySelectorAll(s) { return all(documentElement, s); },
    getElementsByTagName(t) { return documentElement.getElementsByTagName(t); },
    getElementsByClassName(n) { return documentElement.getElementsByClassName(n); },
    addEventListener(type, fn) { (this.listeners[type] = this.listeners[type] || []).push(fn); },
    removeEventListener(type, fn) { const l = this.listeners[type]; if (l) { const i = l.indexOf(fn); if (i >= 0) l.splice(i, 1); } },
    readyState: "loading",
  };

  const timers = [];
  let nextTimer = 1;
  let frames = [];
  let now = 0;
  function addTimer(fn, ms, repeat, args) {
    if (typeof fn !== "function") return 0;
    const id = nextTimer++;
    const every = Math.max(repeat ? 16 : 0, +ms || 0);
    timers.push({ id, fn, at: now + every, every, repeat, args });
    return id;
  }
  function removeTimer(id) { const i = timers.findIndex(t => t.id === id); if (i >= 0) timers.splice(i, 1); }

  const stateListeners = [];
  const panel = {
    state: {},
    width: 0,
    height: 0,
    onState(fn) { if (typeof fn === "function") stateListeners.push(fn); },
    send(name, data) { host("send", JSON.stringify([String(name), data === undefined ? null : data])); },
  };

  const window = globalThis;
  window.window = window;
  window.self = window;
  window.document = document;
  window.panel = panel;
  window.listeners = {};
  window.addEventListener = (type, fn) => { (window.listeners[type] = window.listeners[type] || []).push(fn); };
  window.removeEventListener = (type, fn) => { const l = window.listeners[type]; if (l) { const i = l.indexOf(fn); if (i >= 0) l.splice(i, 1); } };
  window.setTimeout = (fn, ms, ...args) => addTimer(fn, ms, false, args);
  window.setInterval = (fn, ms, ...args) => addTimer(fn, ms, true, args);
  window.clearTimeout = removeTimer;
  window.clearInterval = removeTimer;
  window.requestAnimationFrame = fn => { const id = nextTimer++; frames.push({ id, fn }); return id; };
  window.cancelAnimationFrame = id => { frames = frames.filter(f => f.id !== id); };
  window.performance = { now: () => now };
  window.alert = text => host("log", "alert: " + String(text));
  // Pictures a page can draw: items' icons and a Qbot's pictures. They are there at once.
  class Picture {
    constructor(width, height) { this.localName = "img"; this.srcText = ""; this.onload = null; this.wanted = [width, height]; }
    get src() { return this.srcText; }
    set src(v) { this.srcText = String(v); if (this.onload) setTimeout(() => this.onload && this.onload({ type: "load" }), 0); }
    get complete() { return true; }
    size() { const s = host("image_size", this.srcText); return s ? s.split(",").map(Number) : [0, 0]; }
    get width() { return this.wanted[0] || this.size()[0]; }
    get height() { return this.wanted[1] || this.size()[1]; }
    get naturalWidth() { return this.size()[0]; }
    get naturalHeight() { return this.size()[1]; }
  }
  window.Image = Picture;
  window.HTMLElement = Element;
  window.Element = Element;
  window.Node = Node;
  window.Event = function (type, init) { return makeEvent(type, init || {}); };
  window.CustomEvent = function (type, init) { return makeEvent(type, Object.assign({ detail: init && init.detail }, init || {})); };
  window.localStorage = (() => { const m = new Map(); return { getItem: k => m.has(k) ? m.get(k) : null, setItem: (k, v) => m.set(k, String(v)), removeItem: k => m.delete(k), clear: () => m.clear() }; })();
  const show = a => a.map(x => typeof x === "string" ? x : (() => { try { return JSON.stringify(x); } catch (e) { return String(x); } })()).join(" ");
  window.console = { log: (...a) => host("log", show(a)), info: (...a) => host("log", show(a)), warn: (...a) => host("log", "warning: " + show(a)), error: (...a) => host("log", "error: " + show(a)), debug: () => {} };
  window.fetch = () => Promise.reject(new Error("Web panels can't fetch anything: the program gives the page its data (panel.state)."));
  window.XMLHttpRequest = function () { throw new Error("Web panels can't fetch anything."); };

  function report(e) { host("error", (e && e.stack) ? String(e) + "\n" + e.stack : String(e)); }
  function safely(fn, ...args) { try { fn(...args); } catch (e) { report(e); } }

  // ---- what the game calls -----------------------------------------------------------------------
  globalThis.__qb = {
    // A new page: its HTML (state already filled in), size and state. Runs its scripts.
    load(html, width, height, stateJson, t) {
      now = t;
      panel.width = width; panel.height = height; window.innerWidth = width; window.innerHeight = height;
      try { panel.state = JSON.parse(stateJson); } catch (e) { panel.state = {}; }
      const nodes = parse(html);
      const root = nodes.find(n => n.nodeType === 1 && n.localName === "html");
      if (root) { for (const [k, v] of root.attrs) documentElement.attrs.set(k, v); for (const c of root.childNodes.slice()) documentElement.appendChild(c); }
      else { const body = new Element("body"); for (const n of nodes) body.appendChild(n); documentElement.appendChild(body); }
      const scripts = descendants(documentElement).filter(e => e.localName === "script" && !e.getAttribute("src"));
      for (const s of scripts) {
        try { (0, eval)(s.textContent); } catch (e) { report(e); }
      }
      document.readyState = "complete";
      safely(() => dispatch(documentElement, makeEvent("DOMContentLoaded", { bubbles: false })));
      for (const fn of (document.listeners.DOMContentLoaded || []).concat(window.listeners.load || [])) safely(fn, makeEvent("load", {}));
    },
    // The program changed the state.
    setState(stateJson) {
      try { panel.state = JSON.parse(stateJson); } catch (e) { return; }
      for (const fn of stateListeners) safely(fn, panel.state);
    },
    // A click on an element (by its number), at a point of the page. True if the page's default (data-event) should go on.
    click(number, x, y) {
      const el = byNumber.get(number);
      if (!el) return true;
      let ok = true;
      safely(() => { ok = dispatch(el, makeEvent("click", { clientX: x, clientY: y, button: 0 })); });
      return ok;
    },
    // Typing in a field: "input" while typing, "change" on Enter.
    input(number, type) {
      const el = byNumber.get(number);
      if (el) safely(() => dispatch(el, makeEvent(type, {})));
    },
    // Time passes: due timers and animation frames run. Returns ms until something is due (-1: nothing).
    tick(t) {
      now = t;
      const due = timers.filter(x => x.at <= now).sort((a, b) => a.at - b.at);
      for (const timer of due) {
        if (timer.repeat) timer.at = now + timer.every; else removeTimer(timer.id);
        safely(() => timer.fn(...(timer.args || [])));
      }
      const running = frames;
      frames = [];
      for (const f of running) safely(f.fn, now);
      if (frames.length) return 50;
      let next = -1;
      for (const x of timers) next = next < 0 ? Math.max(0, x.at - now) : Math.min(next, Math.max(0, x.at - now));
      return next;
    },
    // What changed since last asked: the page (if the DOM changed) and each canvas's drawing.
    flush() {
      const out = { html: null, canvases: [] };
      if (domChanged) { out.html = serialize(documentElement, true); domChanged = false; }
      for (const c of canvases.values()) {
        if (c.ops.length || c.full) { out.canvases.push([c.number, c.width, c.height, c.full, c.ops]); c.ops = []; c.full = false; }
      }
      return JSON.stringify(out);
    },
  };
})();
