# YLCloud Webhook v1

## Subscription API

Authenticated Web users manage subscriptions through:

- `GET /api/webhooks/event-types`
- `GET /api/webhooks`
- `POST /api/webhooks`
- `POST /api/webhooks/{subscriptionId}/rotate-secret`
- `DELETE /api/webhooks/{subscriptionId}`

Creation binds one active user API Key. The response returns `whsec_...` exactly once; list responses and database rows never contain plaintext. Rotation returns the new Secret once and keeps the previous signing key for the configured grace period.

## Event catalog

- Files: `FILE_CREATED`, `FILE_UPDATED`, `FILE_DELETED`
- Knowledge: `KNOWLEDGE_INDEXED`, `KNOWLEDGE_REMOVED`, `KNOWLEDGE_FAILED`
- Agent: `AGENT_TASK_COMPLETED`, `AGENT_TASK_FAILED`
- Space authorization: `SPACE_MEMBER_CHANGED`

Every event contains a globally unique `eventId`, `eventType`, monotonically meaningful resource version, occurrence timestamp, resource type/ID and minimal data. Business content is present only when the subscription requests it and the bound API Key still has the required live file/Space Scope.

## Delivery and idempotency

Delivery is at least once. Receivers must persist and deduplicate `eventId`; duplicates are expected after timeouts or a Worker crash after the remote side accepted a request. Cross-resource global ordering is not provided, so receivers compare `resourceVersion` within one resource.

The Worker claims a two-minute lease, sends batches of up to 50, and accepts any 2xx response. HTTP 408, 429, 5xx, network failures and timeouts retry with exponential backoff beginning at five seconds and capped at one hour. Other 4xx responses and exhausted retry budgets enter `DEAD`.

## Signature verification

Headers:

- `X-YLCloud-Event-Id`
- `X-YLCloud-Event-Type`
- `X-YLCloud-Timestamp` (Unix seconds)
- `X-YLCloud-Signature: v1=<hex hmac-sha256>`
- `X-YLCloud-Signature-Previous` during the Secret rotation grace window

Calculate HMAC-SHA256 over the exact UTF-8 bytes of `<timestamp>.<raw request body>` using the subscription Secret and compare in constant time. Reject stale timestamps according to the receiver's replay window, then deduplicate `eventId` before applying business changes.

## SSRF policy

Only HTTP and HTTPS URLs without user-info are accepted. Hostnames are resolved during subscription creation, before every delivery attempt and again for every redirect. Each outbound socket is pinned to the exact IP set that passed that attempt's policy check, while HTTPS still verifies the original hostname and sends its SNI value. By default, loopback, link-local, private, carrier-grade NAT, unspecified and multicast targets are rejected. Only the immutable deployment owner may explicitly enable private targets through `webhook.allowPrivateTargets`; redirect and DNS checks remain active.
