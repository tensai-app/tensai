# websearch

Pure Kotlin/JVM web search and page reading. No Android dependency, no HTTP stack: the host
plugs one in through `HttpFetcher`. Built inside tensai (TEN-180), meant to be extracted later
as `websearch-kt` once the API settles.

## API

```kotlin
interface SearchProvider { val name: String; suspend fun search(query: String, limit: Int): List<SearchResult> }
data class SearchResult(val title: String, val url: String, val snippet: String, val source: String)

class PageReader(http: HttpFetcher, guard: UrlGuard = UrlGuard(), ...) {
    suspend fun read(url: String, maxChars: Int): PageContent   // title first, truncated, flagged
}

val search = FallbackSearch(DuckDuckGoHtml(http), listOf(Wikipedia(http, ua), Marginalia(http)))
```

`HttpFetcher` (get/post, headers, timeout, max bytes) is the only seam. Contract in `Http.kt`:
follow redirects, cap the body, return non-2xx as responses, throw `IOException` only without a
response. JDK `java.net.http` is not on Android, so the app implements it (OkHttp), tests use fakes.

## Errors

Sealed `SearchError`: `Blocked`, `RateLimited(retryAfterSeconds)`, `QuotaExceeded`,
`Unauthorized`, `FormatChanged`, `Network`, `Unsupported`, `UnsafeUrl`.

`FallbackSearch` moves on after `Blocked`, `RateLimited`, `QuotaExceeded`, `Network`, `FormatChanged` or an empty
answer. `Unauthorized` is rethrown at once (the user has to act).

## Providers

| Provider | Access | Limits / terms |
|---|---|---|
| `DuckDuckGoHtml` | `POST html.duckduckgo.com/html/` (form `q`, `l`, `s`), parse `div.result__body` / `h2 a`, `y.js` links = ads, `uddg` redirect unwrapped | no official API, no scraping agreement. HTTP 202 or "anomaly" page = limited. Mobile UA, a request every few seconds, always use a fallback |
| `Wikipedia` | REST `/w/rest.php/v1/search/page`, `language` param | no key. Descriptive User-Agent required (`tensai/<version> (<repo>; <contact>)`) or 403. CC BY-SA, link the article |
| `Tavily` | `POST api.tavily.com/search`, Bearer key, `search_depth: basic` | free without card: 1000 credits/month, 1 per basic request. 429 + `Retry-After`, 432/433 = plan limit |
| `Brave` | `GET api.search.brave.com/res/v1/web/search`, `X-Subscription-Token` | no card-free tier any more: 5 USD / 1000 requests with 5 USD credit per month, card required |
| `Marginalia` | `GET api2.marginalia-search.com/search`, `API-Key: public` | shared key often 503. CC-BY-NC-SA, last resort only |

Keys are always supplied by the user, never shipped. Sources checked 2026-10-17, see the
tensai tooling spec.

## PageReader

- http/https only. `UrlGuard` resolves the host (injectable `HostResolver`) and refuses every
  loopback, link-local, private (10/8, 172.16/12, 192.168/16, 100.64/10, fc00::/7), multicast
  and `.local` / `.localhost` / `.internal` target if any resolved address is one of them.
  Hex and octal IPv4 notations are refused outright. The final URL after redirects is checked
  too, but only after the request: the app's fetcher should validate redirect hops itself.
- text/html only, body capped by the fetcher.
- Drops `script style nav footer aside form header`, prefers `article` / `main`.
- Cloudflare / challenge pages raise `Blocked`. No bypass.

## Tests

`./gradlew :websearch:test`. JVM only, no network: recorded fixtures in `src/test/resources/fixtures`
(DDG results with ads, anomaly and no-results pages, Wikipedia / Tavily / Brave / Marginalia JSON,
Cloudflare challenge, article with navigation noise). A layout change at an engine is meant to
show up there first: refresh the fixture from a real response and fix the parser.
