# API reference

The backend listens on `http://127.0.0.1:8080` by default.

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
