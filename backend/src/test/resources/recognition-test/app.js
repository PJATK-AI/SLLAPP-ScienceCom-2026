const $ = (id) => document.getElementById(id);
const video = $('camera');
const canvas = $('overlay');
const context = canvas.getContext('2d');
const handSelector = $('selected-hand');
const cameraButton = $('camera-toggle');
const socketButton = $('socket-toggle');
const traffic = $('traffic');
const sampleInterval = 100;
const maxEvents = 150;

let tracker;
let normalizeHand;
let skeleton;
let cameraRunning = false;
let socket;
let seq = 0;
let lastAttempt = -Infinity;

function addEvent(direction, title, body, timestamp = Date.now()) {
    const row = document.createElement('li');
    const item = document.createElement('details');
    const summary = document.createElement('summary');
    const time = document.createElement('time');
    const route = document.createElement('span');
    const payload = document.createElement('pre');
    const now = new Date(timestamp);
    time.dateTime = now.toISOString();
    time.textContent = now.toLocaleTimeString('pl-PL', { hour12: false })
        + '.' + String(now.getMilliseconds()).padStart(3, '0');
    route.className = 'direction';
    route.textContent = direction;
    summary.append(time, route, document.createTextNode(title));
    payload.textContent = typeof body === 'string' ? body : JSON.stringify(body, null, 2);
    item.append(summary, payload);
    row.append(item);
    traffic.prepend(row);
    if (traffic.children.length > maxEvents) traffic.lastElementChild.remove();
    $('traffic-empty').hidden = true;
}

function drawHand(hand) {
    const { videoWidth: width, videoHeight: height } = video;
    if (!width || !height) return;
    if (canvas.width !== width || canvas.height !== height) {
        canvas.width = width;
        canvas.height = height;
        $('preview').style.aspectRatio = width + ' / ' + height;
    }
    context.clearRect(0, 0, width, height);
    if (!hand) return;

    const points = hand.landmarks;
    context.strokeStyle = '#94e0ce';
    context.lineWidth = Math.max(2, width / 300);
    context.beginPath();
    for (const { start, end } of skeleton) {
        context.moveTo(points[start].x * width, points[start].y * height);
        context.lineTo(points[end].x * width, points[end].y * height);
    }
    context.stroke();
    context.fillStyle = '#ffe4a5';
    for (const point of points) {
        context.beginPath();
        context.arc(point.x * width, point.y * height, Math.max(3, width / 180), 0, 2 * Math.PI);
        context.fill();
    }
}

function onFrame(hand, timestamp) {
    const selected = hand?.handedness === handSelector.value ? hand : null;
    drawHand(selected);
    $('detection').textContent = selected
        ? 'Wykryto 21 punktów wybranej dłoni'
        : hand ? 'W kadrze tylko druga dłoń' : 'Brak dłoni w kadrze';
    if (socket?.readyState !== WebSocket.OPEN || timestamp - lastAttempt < sampleInterval) return;

    lastAttempt = timestamp;
    if (socket.bufferedAmount > 0) {
        addEvent('Lokalnie', 'Pominięto klatkę', { reason: 'WEBSOCKET_BUSY' });
        return;
    }
    const message = { type: 'no_hand', seq, positionId: null };
    if (selected) {
        try {
            const { score, landmarks } = normalizeHand(selected);
            message.type = 'hand_sample';
            message.hand = {
                coordinateSpace: 'MEDIAPIPE_IMAGE',
                handedness: selected.handedness.toUpperCase(),
                score,
                landmarks,
            };
        } catch (error) {
            addEvent('Lokalnie', 'Pominięto klatkę', { reason: 'INVALID_LANDMARKS', message: error.message });
            return;
        }
    }
    const payload = JSON.stringify(message);
    try {
        socket.send(payload);
        seq++;
        addEvent('WS → backend', message.type + ' · #' + message.seq, payload);
    } catch (error) {
        addEvent('Lokalnie', 'Błąd wysyłania', { message: error.message });
    }
}

function resetCameraDisplay() {
    cameraRunning = false;
    cameraButton.textContent = 'Uruchom kamerę';
    $('camera-placeholder').hidden = false;
    $('detection').textContent = 'Kamera wyłączona';
    context.clearRect(0, 0, canvas.width, canvas.height);
}

function showCameraError(error) {
    resetCameraDisplay();
    const output = $('camera-error');
    output.textContent = error.code
        ? error.message + ' (' + error.code + ')'
        : 'Nie udało się uruchomić kamery. Sprawdź przygotowanie zasobów MediaPipe. ' + error.message;
    output.hidden = false;
    addEvent('Lokalnie', 'Błąd kamery', { code: error.code, message: error.message });
}

async function toggleCamera() {
    $('camera-error').hidden = true;
    if (cameraRunning) {
        tracker.stop();
        resetCameraDisplay();
        return;
    }
    cameraButton.disabled = true;
    $('detection').textContent = 'Uruchamianie kamery…';
    try {
        if (!tracker) {
            const [module, vision] = await Promise.all([
                import('/recognition-test/tracker/index.js'),
                import('@mediapipe/tasks-vision'),
            ]);
            normalizeHand = module.normalizeHandForModel;
            skeleton = vision.HandLandmarker.HAND_CONNECTIONS;
            tracker = module.createHandTracker({
                modelAssetPath: '/recognition-test/mediapipe/hand_landmarker.task',
                wasmPath: '/recognition-test/mediapipe/wasm',
                get preferredHand() { return handSelector.value; },
            });
            tracker.onResult(onFrame);
            tracker.onError(showCameraError);
        }
        await tracker.start(video);
        cameraRunning = true;
        cameraButton.textContent = 'Zatrzymaj kamerę';
        $('camera-placeholder').hidden = true;
    } catch (error) {
        if ($('camera-error').hidden) showCameraError(error);
    } finally {
        cameraButton.disabled = false;
    }
}

function showPrediction(seq, report) {
    const http = report.http;
    if (http) {
        addEvent('HTTP → AI', http.method + ' ' + new URL(http.url).pathname + ' · #' + seq,
            http.requestBody, http.requestedAt);
        if (http.status !== null) {
            const suffix = http.responseTruncated ? ' · treść obcięta' : '';
            addEvent('HTTP ← AI', http.status + ' · #' + seq + ' · ' + report.elapsedMs + ' ms' + suffix,
                http.responseBody ?? '', http.receivedAt);
        }
    }
    const result = report.result;
    const label = result?.letter
        ? result.letter + ' · ' + result.confidence.toLocaleString('pl-PL', { style: 'percent', maximumFractionDigits: 2 })
        : result?.kind ?? report.error ?? 'Brak predykcji';
    $('prediction').textContent = label + ' · ' + report.elapsedMs + ' ms';
}

function receive(event) {
    let message;
    try {
        message = JSON.parse(event.data);
    } catch {
        addEvent('WS ← backend', 'Odpowiedź spoza JSON', event.data);
        return;
    }
    if (message.type === 'prediction_result') showPrediction(message.seq, message.report);
    const suffix = [message.seq === undefined ? null : '#' + message.seq, message.reason ?? message.code]
        .filter(Boolean).join(' · ');
    addEvent('WS ← backend', [message.type, suffix].filter(Boolean).join(' · '), event.data);
}

function toggleSocket() {
    if (socket && socket.readyState < WebSocket.CLOSING) {
        socket.close();
        return;
    }
    seq = 0;
    lastAttempt = -Infinity;
    const url = new URL('/recognition-test/ws', location.href);
    url.protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const current = new WebSocket(url);
    socket = current;
    $('socket-status').textContent = 'Łączenie…';
    socketButton.textContent = 'Rozłącz';
    current.addEventListener('open', () => {
        if (socket !== current) return;
        $('socket-status').textContent = 'Połączony';
        addEvent('WebSocket', 'Otwarto połączenie', { url: url.href });
    });
    current.addEventListener('message', (event) => {
        if (socket === current) receive(event);
    });
    current.addEventListener('close', (event) => {
        if (socket !== current) return;
        socket = undefined;
        $('socket-status').textContent = 'Rozłączony';
        socketButton.textContent = 'Połącz WebSocket';
        addEvent('WebSocket', 'Zamknięto połączenie', { code: event.code, reason: event.reason });
    });
    current.addEventListener('error', () => {
        if (socket === current) addEvent('WebSocket', 'Błąd połączenia', { url: url.href });
    });
}

cameraButton.addEventListener('click', toggleCamera);
socketButton.addEventListener('click', toggleSocket);
$('clear-traffic').addEventListener('click', () => {
    traffic.replaceChildren();
    $('traffic-empty').textContent = 'Brak zdarzeń w dzienniku.';
    $('traffic-empty').hidden = false;
});
window.addEventListener('pagehide', () => {
    tracker?.stop();
    resetCameraDisplay();
    socket?.close();
});
