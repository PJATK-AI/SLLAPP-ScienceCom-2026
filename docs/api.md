# API reference

The backend listens on `http://127.0.0.1:8080` by default.

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
1–20 Unicode code points, without control or format characters. The configured
`sllapp.session.blocked-words` list rejects complete letter sequences after NFKC
normalization and lowercasing with `Locale.ROOT`. Digits and punctuation separate
words; substrings within other words are accepted. The default list contains
common Polish and English profanity. This is a word filter, not full moderation.
`selectedHand` must be `LEFT` or `RIGHT`. Invalid data receives `INVALID_SESSION`.

### Resume a session

```json
{"type":"resume_session","sessionId":"6a386307-3c19-4e3b-90c7-ae405835f825"}
```

Send this on a new connection to receive the stored `game_state`. The server
replaces the previous connection, closing it with code 1000. Each connection
can create or resume only one session; another such command returns
`SESSION_ALREADY_BOUND`. Invalid UUID syntax receives `INVALID_SESSION`;
an unknown or expired UUID closes the connection with code 1008.

Sessions live in memory in one backend process. A restart removes them.
`sllapp.session.creation-ttl` defaults to `5m` from creation; reconnecting does
not extend the expiry time. Disconnecting leaves the session available until
that deadline.

The server checks expiry on incoming messages and scans for expired sessions
every second. The scan removes expired sessions and closes idle connections
with code 1008. Samples and reconnections do not renew the deadline.

### Send hand samples

Both sample types require a bound session, an integer `seq` from 0 to
9007199254740991 and an explicit `positionId: null`. Copy `positionId` from
the latest `game_state`; the implemented `CREATED` state uses null. A missing
or non-null position is invalid. Sequence numbers increase across both types;
gaps are allowed. Reconnecting starts a new sequence. Valid messages with an
old or duplicate sequence are ignored. Invalid messages do not consume it.

`hand_sample` contains a `hand` object with `handedness: LEFT` or `RIGHT` and
exactly 21 `landmarks`. Every point has finite numeric `x`, `y`, `z`; coordinates
are not restricted to 0–1. Extra hand fields, including nested fields, are
retained in the latest accepted sample. Either handedness is accepted regardless
of `selectedHand`. Tracker values `Left`/`Right` must be mapped to `LEFT`/`RIGHT`.

This complete synthetic sample illustrates the format. `score` is optional:

```json
{
  "type": "hand_sample",
  "seq": 0,
  "positionId": null,
  "hand": {
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

When no hand is detected, send:

```json
{"type":"no_hand","seq":1,"positionId":null}
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

The acknowledgement confirms validation and acceptance of a sample.

### Errors and connection closure

Error messages contain `type`, `code` and `message`. They keep the connection
open so the client can correct its request:

| Code | Cause |
| --- | --- |
| `INVALID_MESSAGE` | Malformed JSON, invalid envelope or binary message |
| `UNKNOWN_TYPE` | Unrecognized string `type` |
| `INVALID_SESSION` | Invalid player data or UUID syntax |
| `SESSION_ALREADY_BOUND` | Another create/resume on a bound socket |
| `SESSION_REQUIRED` | A sample sent before create/resume |
| `INVALID_SAMPLE` | Invalid sequence, position or hand data |
| `MESSAGE_TOO_LARGE` | Text exceeds the application limit but not the hard limit |

| Close code | Cause |
| --- | --- |
| `1000` | Another connection resumed the session |
| `1008` | Session expired, or resume named an unknown/expired session |
| `1009` | Message exceeds the hard limit |
| `1011` | Server-side transport error |

### WebSocket configuration

| Spring property | Default | Meaning |
| --- | --- | --- |
| `sllapp.session.creation-ttl` | `5m` | Fixed lifetime from session creation; must be positive |
| `sllapp.session.blocked-words` | PL/EN list in `SessionProperties` | Replacement list of blocked words, containing letters and combining marks |
| `sllapp.websocket.max-message-bytes` | `65536` | Application limit for text, in UTF-8 bytes |
| `sllapp.websocket.transport-max-message-bytes` | `262144` | Hard limit; must exceed the positive application limit |
| `sllapp.websocket.diagnostic-mode` | `false` | Send `sample_ack` for accepted samples |
| `sllapp.web.allowed-origins` | `http://localhost:5173,http://127.0.0.1:5173` | Allowed cross-origin REST and WebSocket callers |

For example, enable acknowledgements when starting the backend from `backend/`:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--sllapp.websocket.diagnostic-mode=true"
```

The session cleanup task runs every second. Configuration can also be supplied
through Spring Boot command-line properties or environment variables, for example
`SLLAPP_WEBSOCKET_DIAGNOSTICMODE=true`.

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
Each stored game contributes one entry; matching nicknames remain separate.
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
