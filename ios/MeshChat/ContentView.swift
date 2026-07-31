import SwiftUI

struct ChatMessage: Identifiable {
    let id = UUID()
    let text: String
    let timestamp: Int64
    let hopRelayed: Bool
}

final class ChatViewModel: ObservableObject, MeshManagerDelegate {
    @Published var messages: [ChatMessage] = []
    @Published var status: String = "starting..."
    @Published var peerCount: Int = 0

    private var meshManager: MeshManager!

    init() {
        meshManager = MeshManager()
        meshManager.delegate = self
    }

    func send(_ text: String) {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        meshManager.sendMessage(text)
    }

    func meshManager(_ manager: MeshManager, didReceiveMessage text: String, timestamp: Int64, hopRelayed: Bool) {
        DispatchQueue.main.async {
            self.messages.insert(ChatMessage(text: text, timestamp: timestamp, hopRelayed: hopRelayed), at: 0)
        }
    }

    func meshManager(_ manager: MeshManager, statusChanged status: String) {
        DispatchQueue.main.async { self.status = status }
    }

    func meshManager(_ manager: MeshManager, peerCountChanged count: Int) {
        DispatchQueue.main.async {
            self.peerCount = count
            self.status = "mesh active — \(count) peer(s) connected"
        }
    }
}

struct ContentView: View {
    @StateObject private var viewModel = ChatViewModel()
    @State private var draft: String = ""

    var body: some View {
        VStack(spacing: 0) {
            Text("Status: \(viewModel.status)")
                .font(.subheadline).bold()
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding()

            List(viewModel.messages) { message in
                VStack(alignment: .leading, spacing: 2) {
                    Text(message.hopRelayed ? "[relayed]" : "[you]")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                    Text(message.text)
                }
            }
            .listStyle(.plain)

            HStack {
                TextField("Type a message", text: $draft)
                    .textFieldStyle(.roundedBorder)
                Button("Send") {
                    viewModel.send(draft)
                    draft = ""
                }
            }
            .padding()
        }
    }
}

#Preview {
    ContentView()
}
