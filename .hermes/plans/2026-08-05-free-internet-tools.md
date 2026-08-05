# Free Internet Access — 5 New Tools

## Goal
Add 5 free, no-API-key-required internet tools so the app has useful web access without any paid service.

## Tools

### 1. `wikipedia_search` — Wikipedia API (free, no key)
- Search: `https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch={query}&format=json&srlimit={limit}`
- Summary: `https://en.wikipedia.org/api/rest_v1/page/summary/{title}`
- Returns: title, snippet, URL, optional summary
- Tool risk: READ_ONLY

### 2. `wikipedia_read` — Wikipedia full article (free, no key)
- Extract: `https://en.wikipedia.org/w/api.php?action=parse&page={title}&format=json&prop=wikitext&section=0`
- Returns: first section as plain text (truncated to 4000 chars)
- Tool risk: READ_ONLY

### 3. `ddg_instant_answer` — DuckDuckGo Instant Answer API (free, no key)
- URL: `https://api.duckduckgo.com/?q={query}&format=json&no_html=1`
- Returns: abstract text, related topics, redirect URL
- Better than HTML scraping — structured JSON, stable API
- Tool risk: READ_ONLY

### 4. `searxng_search` — SearXNG meta-search (free, no key)
- URL: `https://search.inetol.net/search?q={query}&format=json` (reliable public instance)
- Fallback instances: `https://searx.be/search`, `https://search.mdosch.de/search`
- Returns: title, URL, snippet (aggregates Google + Bing + DDG)
- Tool risk: READ_ONLY

### 5. `jina_reader_free` — URL-to-text free tier (free, no key)
- URL: `https://r.jina.ai/{url}` — no auth header = free tier
- Returns: clean markdown text from any URL
- Rate limit: ~10 req/min (acceptable for personal use)
- Tool risk: READ_ONLY (free tier, no cost)

## Architecture
- Each tool is a @Singleton @Inject class in `aura-core/src/main/kotlin/com/aura/tools/`
- All use the shared `OkHttpClient` (no custom client needed)
- SSRF guard on `jina_reader_free` (user-supplied URL)
- No SSRF guard on Wikipedia/DDG/SearXNG (hardcoded base URLs, no user-supplied URLs)
- All registered in `ToolsModule.kt` via `registry.register()`
- Tool count: 70 → 75

## Tests
- One test file per tool with MockWebServer
- Wikipedia: mock search response, mock summary response
- DDG: mock instant answer response
- SearXNG: mock search response
- Jina: mock reader response

## Verification
- tsc → gradlew test → assembleDebug
- 1821 tests → 1821+ new tests, 0 failures