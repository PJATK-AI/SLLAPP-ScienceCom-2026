# API reference

The backend listens on `http://127.0.0.1:8080` by default.
[Local startup](local-development.md) · [Frontend session client](frontend.md#session-client) · [Python prediction API](../model/README.md#prediction-service)

## WebSocket transport

Connect to `ws://127.0.0.1:8080/ws` with a plain WebSocket client. The connection
accepts text JSON objects with a string `type`. Invalid JSON and binary messages
within the transport limit receive an `INVALID_MESSAGE` error.
Unrecognized types receive `UNKNOWN_TYPE`. These errors keep the socket open.

`sllapp.websocket.max-message-bytes` defaults to 65536 UTF-8 bytes per complete
text message. Larger messages receive `MESSAGE_TOO_LARGE` without closing the
socket. The hard limit, `sllapp.websocket.transport-max-message-bytes`, defaults
to 262144 and must exceed the application limit. Larger messages close the
connection with code 1009. The same setting bounds the container buffers;
the handler additionally checks UTF-8 bytes of completed text messages.
Text fragments are assembled by the container before application validation.

`sllapp.web.allowed-origins` controls both `/api/**` CORS and the WebSocket
handshake. Its defaults are `http://localhost:5173` and `http://127.0.0.1:5173`.
Same-origin requests and clients without an `Origin` header are also accepted.

### Create a session

After opening `/ws`, send:

```json
{"type":"create_session","nickname":" Ola ","selectedHand":"RIGHT"}
```

Opening the connection alone does not create a player session or send a state.
The response to `create_session` contains a new UUID and the stored player data:

```json
{
  "type": "game_state",
  "sessionId": "6a386307-3c19-4e3b-90c7-ae405835f825",
  "status": "CREATED",
  "nickname": "Ola",
  "selectedHand": "RIGHT",
  "positionId": null
}
```

The nickname is stripped of leading/trailing Java whitespace and must contain
1–20 Unicode code points, without control or format characters. For moderation,
the server normalizes the nickname with NFKC and lowercases it with `Locale.ROOT`. It
compares complete words and runs of digits against the blocklist. A phrase
matches consecutive tokens in order. Punctuation and whitespace separate
tokens, so the entry `six seven` also matches `six-seven`, and `67` matches
`Ola67`. A longer number such as `167` forms its own token.

The backend reads the blocked-word list once at startup from
`sllapp.moderation.blocked-words-location`. The default file,
`backend/src/main/resources/moderation/blocked-words.txt`, includes Polish and
English words adapted from LDNOOBW under CC BY 4.0. `SOURCES.md` in the same
directory gives the source and the changes. The UTF-8 file takes one entry per
line. Each entry contains one or more words or numbers separated by
whitespace. Words consist of letters and combining marks, and numbers consist
of decimal digits. Blank lines and lines starting with `#` are ignored. A
missing, unreadable, malformed or empty list prevents the backend from starting.

`selectedHand` must be `LEFT` or `RIGHT`. Invalid data receives `INVALID_SESSION`.

The server looks up the nickname across all saved results. For this lookup it
strips leading and trailing Java whitespace, normalizes to Unicode NFC,
lowercases with `Locale.ROOT`, and applies NFC again. `Ola`, `ola` and ` Ola `
therefore share one ranking entry.

If a result exists, the server returns `NICKNAME_EXISTS` and leaves the
connection unbound. After the player confirms, send `create_session` again
with `confirmExistingNickname: true` on the same connection or a new one.
The new session uses the display nickname from the saved entry.

```json
{"type":"create_session","nickname":"ola","selectedHand":"RIGHT","confirmExistingNickname":true}
```

`confirmExistingNickname` is optional and must be a JSON boolean when sent.
Omitting it or sending `false` requires confirmation for a saved nickname.
A storage lookup failure returns `SESSION_STORAGE_UNAVAILABLE` and leaves
the connection unbound so the request can be retried.

### Start a game

Send this on the connection bound to a `CREATED` session.

```json
{"type":"start_game"}
```

The server starts one game and returns its current position.

```json
{
  "type": "game_state",
  "sessionId": "6a386307-3c19-4e3b-90c7-ae405835f825",
  "status": "RUNNING",
  "nickname": "Ola",
  "selectedHand": "RIGHT",
  "positionId": 1,
  "word": "LAS",
  "letterIndex": 0,
  "expectedLetter": "L",
  "remainingMs": 60000,
  "score": 0,
  "requiresRelease": false
}
```

`letterIndex` is zero-based. `expectedLetter` is a one-character string.
`requiresRelease` is set when the active letter repeats the preceding completed
letter, including across words. The server sends a fresh `RUNNING` state after
each completion, when the hand is released and on resume.
`remainingMs` is the remaining whole milliseconds at the time of that snapshot.
The server uses the configured scoring values. The default rules award 50
points for completing a three-letter word and 180 for a four-letter word.
[Scoring formula](backend-architecture.md#standalone-game-engine)

An unbound connection receives `SESSION_REQUIRED`. A second `start_game` on
an available session receives an error and leaves the game deadline in place.

```json
{"type":"error","code":"GAME_ALREADY_STARTED","message":"This session already started a game."}
```

### Finish a game

At the game deadline, scoring ends and the server submits the round's score to
the ranking, even if the player disconnected. The first result creates the
nickname's entry. A higher score replaces the stored score, finish time and
session identifier. A lower or equal score keeps the stored record. The current
connection receives the final state with the score from this round. A successful
resume after the deadline returns that same round score.

```json
{
  "type": "game_state",
  "sessionId": "6a386307-3c19-4e3b-90c7-ae405835f825",
  "status": "FINISHED",
  "nickname": "Ola",
  "selectedHand": "RIGHT",
  "positionId": null,
  "remainingMs": 0,
  "score": 120
}
```

### Resume a session

```json
{"type":"resume_session","sessionId":"6a386307-3c19-4e3b-90c7-ae405835f825"}
```

Send this on a new connection to receive the current `game_state`. The server
replaces the previous connection, closing it with code 1000. Each connection
can create or resume only one session; another such command returns
`SESSION_ALREADY_BOUND`. Invalid UUID syntax receives `INVALID_SESSION`;
an unknown or expired UUID closes the connection with code 1008.

Sessions live in memory in one backend process. A restart removes them.
A session in `CREATED` can start or resume before its creation expiry
(`sllapp.session.creation-ttl`, default `5m`). Once started, it can resume until
the game deadline plus `sllapp.session.finished-ttl` (default `5m`). The game
deadline is the accepted start time plus `sllapp.session.game-duration`
(default `60s`). These deadlines stay fixed through activity and reconnection.

The server checks deadlines on incoming messages and in a sweep every second.
The sweep finishes due games and closes expired sockets with code 1008.
The stored ranking timestamp is the game deadline, including when the sweep
observes it later. A failed result write is retried by the sweep while the
backend process runs, including after the resume window expires.

### Send hand samples

Both sample types require a bound session and an integer `seq` from 0 to
9007199254740991. In `CREATED` and `RUNNING`, copy `positionId` from the latest
`game_state`.

| State | Sample behavior |
| --- | --- |
| `CREATED` | Requires an explicit `positionId: null` for the camera check. |
| `RUNNING` | Requires the active positive safe integer `positionId`. Null, missing, malformed and stale IDs receive `INVALID_SAMPLE`. |
| `FINISHED` | Both sample types receive `GAME_FINISHED`. The connection stays open until expiry. |

Sequence numbers increase across both types on the same connection. Gaps are
allowed, and reconnecting starts a new sequence. Valid messages with an old or
duplicate sequence are ignored. Invalid messages do not consume a sequence
number. In `RUNNING`, fresh samples are processed by the recognition rules below.

`hand_sample` contains a `hand` object with `handedness: LEFT` or `RIGHT` and
exactly 21 `landmarks`. Every point has finite numeric `x`, `y`, `z`; coordinates
are not restricted to 0–1. Extra hand fields, including nested fields, are
retained and passed to the prediction service for eligible samples. Either
handedness passes transport validation. The game uses the session's
`selectedHand`. Tracker values `Left`/`Right` must be mapped to `LEFT`/`RIGHT`.

`score` and `coordinateSpace` are optional in the WebSocket sample. The
[Python prediction API](../model/README.md#post-predict) requires
`coordinateSpace: "MEDIAPIPE_IMAGE"` inside the `hand` object.

```json
{
  "type": "hand_sample",
  "seq": 0,
  "positionId": null,
  "hand": {
    "coordinateSpace": "MEDIAPIPE_IMAGE",
    "handedness": "RIGHT",
    "score": 0.95,
    "landmarks": [
      {"x":0.50,"y":0.70,"z":0.00},
      {"x":0.48,"y":0.65,"z":-0.01},
      {"x":0.46,"y":0.60,"z":-0.02},
      {"x":0.44,"y":0.55,"z":-0.03},
      {"x":0.42,"y":0.50,"z":-0.04},
      {"x":0.48,"y":0.52,"z":-0.02},
      {"x":0.48,"y":0.44,"z":-0.03},
      {"x":0.48,"y":0.38,"z":-0.04},
      {"x":0.48,"y":0.32,"z":-0.05},
      {"x":0.52,"y":0.50,"z":-0.02},
      {"x":0.52,"y":0.42,"z":-0.03},
      {"x":0.52,"y":0.36,"z":-0.04},
      {"x":0.52,"y":0.30,"z":-0.05},
      {"x":0.56,"y":0.52,"z":-0.02},
      {"x":0.56,"y":0.44,"z":-0.03},
      {"x":0.56,"y":0.38,"z":-0.04},
      {"x":0.56,"y":0.32,"z":-0.05},
      {"x":0.60,"y":0.55,"z":-0.01},
      {"x":0.60,"y":0.49,"z":-0.02},
      {"x":0.60,"y":0.44,"z":-0.03},
      {"x":0.60,"y":0.39,"z":-0.04}
    ]
  }
}
```

When no hand is detected during the camera check, send:

```json
{"type":"no_hand","seq":1,"positionId":null}
```

For an active game, use its current position ID.

```json
{"type":"no_hand","seq":2,"positionId":1}
```

Samples before creating/resuming a session receive `SESSION_REQUIRED`.
Invalid samples receive an error and the connection stays open, for example:

```json
{"type":"error","code":"INVALID_SAMPLE","message":"positionId must be null in CREATED state."}
```

`sllapp.websocket.diagnostic-mode` defaults to `false`. When enabled, every
fresh valid sample receives an acknowledgement:

```json
{"type":"sample_ack","seq":1}
```

The acknowledgement confirms transport acceptance, including when recognition
skips the sample.

### Letter recognition

During `RUNNING`, the server sends fresh samples of the selected hand for the
active `positionId` to the prediction service. Each session has one prediction
in flight. Additional samples of the selected hand are skipped while that call
is outstanding, with no queue. `no_hand` takes effect immediately and
invalidates an outstanding result. A sample of the other hand also invalidates
it. The slot stays occupied until the call ends, even if the socket is replaced.

A result can count while the round, socket and position are current and the
time since the server accepted the sample is below `sllapp.ai.timeout`. The
prediction must match the expected letter at or above `min-confidence`.
The server awards points, advances the position and sends `game_state` once
the same series reaches both `required-predictions` and `min-hold-duration`.
The hold time runs from the first to the latest qualifying sample. The server
measures it and the gaps between samples using monotonic sample acceptance
times. If the count reaches its threshold first, each subsequent qualifying
prediction in the series can satisfy the hold requirement. Set
`min-hold-duration` to `0ms` to use the prediction count alone.

A different letter, low confidence, a prediction failure or timeout,
`no_hand`, a sample of the other hand, a position change or session resumption
resets the count and hold time. A gap greater than `max-sample-gap` starts a
new series with the current qualifying sample. A gap equal to the limit
continues the existing series.

While `requiresRelease` is true, consecutive `no_hand` messages for the active
position count toward release. Send `no_hand` whenever the selected hand is
absent, including when only the other hand is visible. Any hand sample resets
the release count. At the configured count, the server sends `game_state` with
`requiresRelease: false` and the same position and score. The first subsequent
qualifying sample starts a new series. On resume, the release flag is preserved
and any partial release count starts at zero.

### Round prediction diagnostics

When `sllapp.websocket.diagnostic-mode` is enabled, a client can connect to
`/ws?diagnostics=1` to receive best-effort `prediction_result` messages
for its own round. The message describes the same AI attempt the game used.
It identifies the accepted sample by `seq` and `positionId`:

```json
{
  "type": "prediction_result",
  "seq": 12,
  "positionId": 3,
  "elapsedMs": 9,
  "disposition": "POSITION_COMPLETED",
  "letter": "A",
  "confidence": 0.96,
  "httpStatus": 200
}
```

`elapsedMs` runs from sample acceptance until the prediction resolves. A
failure has a `failure` category in place of `letter` and `confidence` and
includes `httpStatus` when available. `PROCESSED` means the result reached
the recognition rules. `POSITION_COMPLETED` means it advanced the round.
`TOO_LATE` and `IGNORED` describe results that the game discarded. A busy
diagnostics sender can skip a report. Use `game_state` for the actual round
progress and `sample_ack` for transport acceptance.

### Errors and connection closure

Error messages contain `type`, `code` and `message`. They keep the connection
open so the client can correct its request:

| Code | Cause |
| --- | --- |
| `INVALID_MESSAGE` | Malformed JSON, invalid envelope or binary message |
| `UNKNOWN_TYPE` | Unrecognized string `type` |
| `INVALID_SESSION` | Invalid player data or UUID syntax |
| `SESSION_ALREADY_BOUND` | Another create/resume on a bound socket |
| `SESSION_REQUIRED` | Start or sample sent before create/resume |
| `GAME_ALREADY_STARTED` | Start sent after this session already started a game |
| `GAME_FINISHED` | Sample sent during the finished session's resume window |
| `INVALID_SAMPLE` | Invalid sequence, position or hand data |
| `MESSAGE_TOO_LARGE` | Text exceeds the application limit but not the hard limit |

| Close code | Cause |
| --- | --- |
| `1000` | Another connection resumed the session |
| `1008` | Session expired, or resume named an unknown/expired session |
| `1009` | Message exceeds the hard limit |
| `1011` | Server-side transport error |

### WebSocket configuration

The session, scoring and recognition defaults come from
`backend/src/main/resources/application.yml`. Invalid settings fail startup
with an error that identifies the property.

| Spring property | Default | Meaning |
| --- | --- | --- |
| `sllapp.session.creation-ttl` | `5m` | Time to start or resume after creation. Must be positive. |
| `sllapp.session.game-duration` | `60s` | Time from accepted start to the game deadline. Must be positive. |
| `sllapp.session.finished-ttl` | `5m` | Resume window measured from the game deadline. Must be positive. |
| `sllapp.moderation.blocked-words-location` | `classpath:moderation/blocked-words.txt` | Blocked-word file; must start with `classpath:` or `file:` |
| `sllapp.websocket.max-message-bytes` | `65536` | Application limit for text, in UTF-8 bytes |
| `sllapp.websocket.transport-max-message-bytes` | `262144` | Hard limit; must exceed the positive application limit |
| `sllapp.websocket.diagnostic-mode` | `false` | Send `sample_ack` for accepted samples and allow opted-in round prediction reports |
| `sllapp.web.allowed-origins` | `http://localhost:5173,http://127.0.0.1:5173` | Allowed cross-origin REST and WebSocket callers |
| `sllapp.game.scoring.letter-points` | `10` | Nonnegative base points for a correct letter before the multiplier. |
| `sllapp.game.scoring.word-bonus` | `20` | Nonnegative base bonus for completing a word, scaled by the same multiplier. |
| `sllapp.game.scoring.multiplier-step` | `2` | Nonnegative increase in the multiplier for each letter beyond three. |
| `sllapp.game.recognition.min-confidence` | `0.80` | Inclusive prediction confidence threshold. Must be finite and between 0 and 1. |
| `sllapp.game.recognition.required-predictions` | `3` | Consecutive qualifying predictions required to complete a position. Must be positive. |
| `sllapp.game.recognition.max-sample-gap` | `350ms` | Inclusive maximum gap between acceptance times of consecutive counted samples. Must be positive. |
| `sllapp.game.recognition.release-no-hand-count` | `2` | Consecutive `no_hand` events required to unlock a repeated letter. Must be positive. |
| `sllapp.game.recognition.min-hold-duration` | `250ms` | Inclusive minimum span of a qualifying series, measured between sample acceptance times. Must be nonnegative. `0ms` disables the hold time requirement. |
| `sllapp.ai.timeout` | `250ms` | HTTP timeout and exclusive time budget from sample acceptance to applying a prediction. Must be positive. |

Use Spring Boot command-line properties or environment variables such as
`SLLAPP_WEBSOCKET_DIAGNOSTICMODE=true`.
[Enable WebSocket acknowledgements](local-development.md#enable-websocket-acknowledgements)

### Try the session protocol

With the backend and Vite running, open the browser console on the local app.

```js
const socket = new WebSocket('ws://127.0.0.1:8080/ws');
socket.onmessage = ({ data }) => console.log(JSON.parse(data));
socket.onopen = () => socket.send(JSON.stringify({
    type: 'create_session',
    nickname: 'Ola',
    selectedHand: 'RIGHT',
}));
```

After the server reports `CREATED`, send
`socket.send(JSON.stringify({ type: 'start_game' }))` and look for `RUNNING`.
Use the latest `positionId` when sending samples during the game.

## Read the ranking

`GET /api/ranking?limit=N`

`limit` is optional and defaults to 10. It must be an integer from 1 to 100.
An empty value is invalid.

```sh
curl "http://127.0.0.1:8080/api/ranking?limit=2"
```

The response is `200 OK` with `Content-Type: application/json`:

```json
[
  { "rank": 1, "nickname": "Ola", "score": 120 },
  { "rank": 2, "nickname": "Jan", "score": 100 }
]
```

Results follow score descending, finish time ascending and session identifier
ascending. `rank` is the position in that order, starting at 1, including ties.
Each nickname contributes one entry with its highest saved score. Its display
nickname comes from the first saved entry or from the best record selected
during migration of an existing database.
The response contains at most `limit` entries. An empty database returns `[]`.

## Invalid limit

Empty, non-integer, overflowing and out-of-range values return `400 Bad Request`
with `Content-Type: application/problem+json`. For example, `?limit=101` returns:

```json
{
  "title": "Bad Request",
  "status": 400,
  "detail": "limit must be an integer between 1 and 100.",
  "instance": "/api/ranking"
}
```
