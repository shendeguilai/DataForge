#!/usr/bin/env swift

import AppKit
import Foundation
import Vision

struct CardText: Codable {
    let fileName: String
    let lines: [String]
}

guard CommandLine.arguments.count == 2 else {
    FileHandle.standardError.write(Data("用法: extract_quiz_cards.swift <题卡目录>\n".utf8))
    exit(2)
}

let directory = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
let fileManager = FileManager.default
let files = try fileManager.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
    .filter { $0.pathExtension.lowercased() == "png" }
    .sorted { $0.lastPathComponent < $1.lastPathComponent }

func recognize(_ url: URL) throws -> CardText {
    guard let image = NSImage(contentsOf: url),
          let cgImage = image.cgImage(forProposedRect: nil, context: nil, hints: nil) else {
        throw NSError(domain: "QuizCardOCR", code: 1,
                      userInfo: [NSLocalizedDescriptionKey: "无法读取 \(url.lastPathComponent)"])
    }

    let request = VNRecognizeTextRequest()
    request.recognitionLevel = .accurate
    request.recognitionLanguages = ["zh-Hans", "en-US"]
    request.usesLanguageCorrection = false
    request.minimumTextHeight = 0.012
    try VNImageRequestHandler(cgImage: cgImage, options: [:]).perform([request])

    let observations = (request.results ?? []).sorted { left, right in
        let verticalDifference = left.boundingBox.midY - right.boundingBox.midY
        if abs(verticalDifference) > 0.015 { return verticalDifference > 0 }
        return left.boundingBox.minX < right.boundingBox.minX
    }
    let lines = observations.compactMap { $0.topCandidates(1).first?.string }
        .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
        .filter { !$0.isEmpty }
    return CardText(fileName: url.lastPathComponent, lines: lines)
}

var cards: [CardText] = []
for (index, file) in files.enumerated() {
    autoreleasepool {
        do {
            cards.append(try recognize(file))
            FileHandle.standardError.write(Data("[\(index + 1)/\(files.count)] \(file.lastPathComponent)\n".utf8))
        } catch {
            FileHandle.standardError.write(Data("OCR 失败: \(error.localizedDescription)\n".utf8))
            exit(1)
        }
    }
}

let encoder = JSONEncoder()
encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
FileHandle.standardOutput.write(try encoder.encode(cards))
FileHandle.standardOutput.write(Data("\n".utf8))
