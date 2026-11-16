# API reference

The backend listens on `http://127.0.0.1:8080` by default.

## WebSocket transport

Connect to `ws://127.0.0.1:8080/ws` with a plain WebSocket client. The connection
accepts text JSON objects with a string `type`. Invalid JSON and binary messages
receive `{"type":"error","code":"INVALID_MESSAGE","message":"..."}`.
Unrecognized types receive `UNKNOWN_TYPE`. These errors keep the socket open.

`sllapp.websocket.max-message-bytes` defaults to 65536 UTF-8 bytes per complete
text message. Larger messages receive `MESSAGE_TOO_LARGE` without closing the
socket. The container buffer limit, `sllapp.websocket.transport-max-message-bytes`,
defaults to 262144 and must exceed the application limit. Messages exceeding
the container limit close the connection. Text fragments are assembled by the
container before application validation.

`sllapp.web.allowed-origins` controls both `/api/**` CORS and the WebSocket
handshake. Its defaults are `http://localhost:5173` and `http://127.0.0.1:5173`.
Clients without an `Origin` header can connect.

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
