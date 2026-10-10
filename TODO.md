## Small separate ideas
- Shrink the release APK (~223 MB): it bundles the embedding model plus ONNX native libs for
  four ABIs. An abiFilters/ABI-split config (arm64-only, or per-ABI APKs) would cut download size
  a lot for sideloading.
- Nexusmods RSS support = Blocked: the feeds (nexusmods.com/<game>/rss) sit behind a
  Cloudflare JS challenge that returns 403 to a plain HTTP client (curl and the app's
  Ktor client alike, even with the Firefox UA). Would need a browser engine to clear it.
  There is also no per-user Tracking Centre feed. Parked unless that changes.
- 4chan: Archive fallback for deleted thread.
- 4chan: Some thread don't have title and the app duplicate the body text instead. To fix it: Litteral <no-title> as title or just nothing at all. 
- Apps crash when updating feeds.
- Image/video viewer should also support comments.
- Secrets should be stored in Kwallet/gnome-keyring/Pass on Linux and Android's native secrets manager.
