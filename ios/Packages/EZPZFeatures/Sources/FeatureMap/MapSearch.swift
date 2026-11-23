import CoreGeo
import CoreModel
import DesignSystem
import SwiftUI

/// The search: a grid at any precision or a lat/long in any notation, read as it is typed (`PlaceSearch.read`, the web's target field),
/// its places listed under it nearest the map first; a choice flies the map there and sets nothing else. An unsigned longitude is
/// offered both ways and never guessed between. On iPhone it folds into a round button on the row and opens across it; on iPad it
/// is always open.
public struct MapSearchField: View {
    let store: MapStore
    @Binding var text: String
    /// Nil where it is always open (iPad); else whether it is open now.
    @Binding var open: Bool
    let foldable: Bool
    @FocusState private var focused: Bool
    @Environment(\.palette) private var palette

    public init(store: MapStore, text: Binding<String>, open: Binding<Bool>, foldable: Bool) {
        self.store = store
        _text = text
        _open = open
        self.foldable = foldable
    }

    public var body: some View {
        if foldable && !open {
            MapButton("Search for a grid or coordinate", symbol: "magnifyingglass") {
                open = true
            }
            .frame(height: 40)
        } else {
            VStack(alignment: .leading, spacing: 8) {
                field
                list
            }
            .onAppear { if foldable { focused = true } }
        }
    }

    private var field: some View {
        HStack(spacing: 4) {
            if foldable {
                Button {
                    fold()
                } label: {
                    Image(systemName: "chevron.backward").frame(width: 40, height: 40).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Close the search")
            } else {
                Image(systemName: "magnifyingglass").foregroundStyle(palette.onSurfaceVariant).frame(width: 40, height: 40)
                    .accessibilityHidden(true)
            }
            TextField("Grid or coordinate", text: $text)
                .font(.system(.body, design: .monospaced))
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
                .keyboardType(.asciiCapable)
                .submitLabel(.search)
                .focused($focused)
                .onSubmit { if store.search(text) { done() } }
            if !text.isEmpty {
                Button {
                    text = ""
                } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(palette.onSurfaceVariant).frame(width: 40, height: 40)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Clear")
            }
        }
        .padding(.horizontal, 4)
        .frame(minHeight: 48)
        .mapGlass(interactive: false)
    }

    @ViewBuilder private var list: some View {
        let reading = store.reading(text)
        if focused && (!reading.choices.isEmpty || reading.message != nil) {
            VStack(alignment: .leading, spacing: 0) {
                if let message = reading.message {
                    Text(message)
                        .font(.footnote)
                        .foregroundStyle(palette.onSurfaceVariant)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                        .accessibilityAddTraits(.updatesFrequently)
                }
                ForEach(reading.choices, id: \.self) { choice in
                    Button {
                        store.go(to: choice)
                        done()
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(choice.isGrid ? choice.mgrs : choice.latLong).font(.system(.body, design: .monospaced))
                            Text("\(choice.isGrid ? choice.latLong : choice.mgrs) · \(choice.how)")
                                .font(.caption)
                                .foregroundStyle(palette.onSurfaceVariant)
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                        .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
            .mapGlass(RoundedRectangle(cornerRadius: 20), interactive: false)
        }
    }

    private func done() {
        focused = false
        if foldable { open = false }
    }

    private func fold() {
        focused = false
        open = false
    }
}
