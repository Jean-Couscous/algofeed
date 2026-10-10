## Small separate ideas
- Nexusmods RSS support = Blocked: the feeds (nexusmods.com/<game>/rss) sit behind a
  Cloudflare JS challenge that returns 403 to a plain HTTP client (curl and the app's
  Ktor client alike, even with the Firefox UA). Would need a browser engine to clear it.
  There is also no per-user Tracking Centre feed. Parked unless that changes.
