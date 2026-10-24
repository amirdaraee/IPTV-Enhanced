import ImageIO
import SwiftUI
import UIKit

/// Loads artwork (channel logos, posters) with a decoded-image memory cache, a disk cache
/// and request coalescing. Images are decoded off the main thread and downsampled to the
/// size they are drawn at, so a 2000px poster costs a few hundred KB instead of 16 MB and
/// scrolling back over a row never re-downloads or re-decodes it.
final class ImagePipeline: @unchecked Sendable {
    static let shared = ImagePipeline()

    private let memory: NSCache<NSString, UIImage> = {
        let c = NSCache<NSString, UIImage>()
        c.totalCostLimit = 96 * 1024 * 1024
        return c
    }()
    private let session: URLSession
    private let lock = NSLock()
    private var inFlight: [NSString: Task<UIImage?, Never>] = [:]

    private init() {
        let config = URLSessionConfiguration.default
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first?.appendingPathComponent("artwork")
        config.urlCache = URLCache(memoryCapacity: 0, diskCapacity: 256 * 1024 * 1024, directory: dir)
        // Logos and posters rarely change and many panels send no cache headers: prefer the disk copy.
        config.requestCachePolicy = .returnCacheDataElseLoad
        config.timeoutIntervalForRequest = 20
        config.httpMaximumConnectionsPerHost = 6
        session = URLSession(configuration: config)
    }

    private static func key(_ url: URL, _ maxPixels: Int) -> NSString { "\(maxPixels)|\(url.absoluteString)" as NSString }

    /// Memory-cache hit only; safe to call while building a view.
    func cached(_ url: URL, maxPixels: Int) -> UIImage? { memory.object(forKey: Self.key(url, maxPixels)) }

    func load(_ url: URL, maxPixels: Int) async -> UIImage? {
        let key = Self.key(url, maxPixels)
        if let hit = memory.object(forKey: key) { return hit }
        let task: Task<UIImage?, Never> = lock.withLock {
            if let running = inFlight[key] { return running }
            let t = Task.detached(priority: .utility) { [session, memory] () -> UIImage? in
                guard let (data, response) = try? await session.data(from: url),
                      (response as? HTTPURLResponse).map({ (200..<300).contains($0.statusCode) }) ?? true,
                      let image = Self.decode(data, maxPixels: maxPixels) else { return nil }
                memory.setObject(image, forKey: key, cost: Int(image.size.width * image.size.height * image.scale * image.scale * 4))
                return image
            }
            inFlight[key] = t
            return t
        }
        let image = await task.value
        _ = lock.withLock { inFlight.removeValue(forKey: key) }
        return image
    }

    /// Decodes straight to a thumbnail no larger than `maxPixels` on its longest side.
    private static func decode(_ data: Data, maxPixels: Int) -> UIImage? {
        let options = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithData(data as CFData, options) else { return nil }
        let thumb: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixels,
        ]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, thumb as CFDictionary) else { return nil }
        return UIImage(cgImage: cg)
    }
}

/// Remote image drawn from `ImagePipeline`; shows nothing until loaded (callers draw a placeholder behind it).
struct CachedImage: View {
    let url: URL
    let maxPixels: Int
    let contentMode: ContentMode
    @State private var image: UIImage?

    init(url: URL, maxPixels: Int, contentMode: ContentMode) {
        self.url = url
        self.maxPixels = maxPixels
        self.contentMode = contentMode
        // A memory hit renders in the first frame, with no placeholder flash while scrolling.
        _image = State(initialValue: ImagePipeline.shared.cached(url, maxPixels: maxPixels))
    }

    var body: some View {
        ZStack {
            if let image {
                Image(uiImage: image).resizable().aspectRatio(contentMode: contentMode)
            }
        }
        .task(id: url) {
            // Also runs when the view is reused for another URL: never show the previous image.
            image = ImagePipeline.shared.cached(url, maxPixels: maxPixels)
            if image == nil { image = await ImagePipeline.shared.load(url, maxPixels: maxPixels) }
        }
    }
}
