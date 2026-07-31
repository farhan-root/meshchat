# MeshChat — Core Bluetooth Mesh Chat (bitchat-style, no encryption yet)

Send text messages phone-to-phone over Bluetooth Low Energy with **zero SIM,
Wi-Fi, or internet**. Any phone with the app in Bluetooth range receives and
automatically relays messages further, flooding the mesh — same core idea as
bitchat, minus encryption for now.

Android and iOS use **identical service/characteristic UUIDs and packet
format**, so a phone of either OS can relay to the other.

## How the mesh works

- Every phone is simultaneously a BLE **peripheral** (advertises itself,
  hosts a GATT characteristic others can write into) and a **central**
  (scans for other MeshChat phones, connects, and writes messages to them).
- Sending a message packs it as: `[16-byte message ID][1-byte TTL][8-byte
  timestamp][UTF-8 text]` and writes it to every currently connected peer.
- When a phone receives a packet, it checks the message ID against a
  "seen" cache. New messages are shown to the user and — if TTL > 0 —
  re-broadcast with TTL−1 to all of *that* phone's connected peers. Already-seen
  IDs are dropped, which prevents infinite relay loops.
- Default TTL is 5 hops. No message is stored beyond the in-memory seen-cache;
  nothing is written to disk or sent anywhere off-mesh.

## What's NOT included yet (by design — you asked for core-only)

- No encryption — messages are sent in plaintext over BLE. Don't use this for
  sensitive content yet.
- No usernames/identity system, no private 1:1 messaging (everything is
  broadcast to the whole mesh).
- No store-and-forward for offline peers (bitchat has this; this version only
  relays to phones currently connected).

Both are addable later — ask me when you're ready to layer them in.

---

## Android build steps

1. Install **Android Studio** (free, from developer.android.com).
2. Open Android Studio → "Open" → select the `android/` folder from this
   project.
3. Let Gradle sync (it will download dependencies the first time — needs
   internet just for this one-time setup step).
4. Plug in an Android phone (Settings → About Phone → tap "Build number" 7
   times to enable Developer Mode → enable USB debugging), or use two
   emulators (emulators don't support real BLE, so for real testing you need
   two physical phones).
5. Click Run ▶ with your phone selected as the target.
6. On first launch, grant the Bluetooth permission prompts.
7. Minimum Android version: **Android 8.0 (API 26)**, for BLE peripheral
   (advertising) support.

## iOS build steps

1. Install **Xcode** (free, from the Mac App Store — requires a Mac).
2. Open Xcode → "Create New Project" → iOS → App.
   - Product Name: `MeshChat`
   - Interface: SwiftUI
   - Language: Swift
3. Delete the auto-generated `ContentView.swift` and `MeshChatApp.swift`
   Xcode creates, and drag in the three files from `ios/MeshChat/` in this
   project instead (`MeshManager.swift`, `ContentView.swift`,
   `MeshChatApp.swift`).
4. Add the Bluetooth permission keys: select your project → target →
   **Info** tab → add the two keys and the background modes listed in
   `Info-additions.plist` (Xcode manages Info.plist automatically for new
   SwiftUI projects, so add these as rows in the Info tab rather than editing
   a plist file directly).
5. Under target → **Signing & Capabilities**, sign in with your Apple ID
   (free) and select your personal team, so you can install to your own
   device.
6. Plug in an iPhone, select it as the run destination, click Run ▶.
   **BLE peripheral mode does not work in the iOS Simulator** — you need a
   real iPhone.
7. On first launch, grant the Bluetooth permission prompt.
8. Minimum iOS version: iOS 13+ (CoreBluetooth peripheral support).

---

## Testing the mesh

- Install on 2+ phones (any mix of Android/iPhone), keep Bluetooth on, open
  the app on each.
- Wait a few seconds for "Status: mesh active — N peer(s) connected" to show
  a peer count > 0 (this confirms they've found and connected to each other).
- Type a message on one phone and hit Send — it should appear on the others
  within a couple seconds, tagged `[relayed]`.
- To test multi-hop relaying, put a third phone **out of direct range** of
  the sender but in range of the second phone — the message should still
  arrive via relay through the middle phone.

## Known rough edges (core version)

- BLE connection limits vary by device (typically 4–7 simultaneous
  connections), so very dense crowds will need proper mesh routing
  optimization later — fine for testing with a handful of phones now.
- No de-duplication of *identical retyped* messages — only exact same
  message ID (i.e., re-relays of the same send) are deduped.
- No UI for peer list / message history persistence — messages disappear on
  app restart.
