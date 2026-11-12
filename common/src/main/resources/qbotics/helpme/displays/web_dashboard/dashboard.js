// the page's script (JavaScript). It runs in each player's game, not on the computer.
const canvas = document.getElementById("chart");
const chart = canvas.getContext("2d");
const history = [];

function draw() {
  const w = canvas.width, h = canvas.height;
  chart.fillStyle = "#0b1a26";
  chart.fillRect(0, 0, w, h);
  // A line every 25 degrees.
  chart.strokeStyle = "#1f3a50";
  chart.lineWidth = 1;
  for (let y = 0; y < h; y += 25) {
    chart.beginPath(); chart.moveTo(0, y); chart.lineTo(w, y); chart.stroke();
  }
  // The heat, the newest on the right.
  chart.strokeStyle = "#ffb347";
  chart.lineWidth = 2;
  chart.beginPath();
  history.forEach((heat, i) => {
    const x = w - (history.length - 1 - i) * 6;
    const y = h - heat * h / 150;
    if (i === 0) chart.moveTo(x, y); else chart.lineTo(x, y);
  });
  chart.stroke();
  chart.fillStyle = "#e6e8ea";
  chart.font = "11px sans-serif";
  chart.fillText("Heat (last minute)", 6, 14);
}

// The program set web.state: show the new heat.
panel.onState(state => {
  const heat = document.getElementById("heat");
  heat.textContent = state.heat + "°";
  heat.className = "big " + (state.heat > 100 ? "hot" : "ok");
  history.push(state.heat);
  if (history.length > 61) history.shift();
  document.getElementById("note").textContent = state.note || "";
  draw();
});

// The button: tell the program (its @web.on("cool") runs).
document.getElementById("cool").addEventListener("click", () => {
  panel.send("cool", { amount: 25 });
});

draw();
