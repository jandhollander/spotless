/*
 * Copyright 2021 DiffPlug
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.diffplug.spotless.maven.incremental;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.newBufferedReader;
import static java.nio.file.Files.newBufferedWriter;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.TreeMap;

import org.apache.maven.plugin.logging.Log;

// todo: use TrieMap for fileToLastModifiedTime with String keys
// todo: FileTime -> Instant can be a lossy conversion
// todo: add unit tests
class FileIndex {

	private static final String SEPARATOR = " ";

	private final Path indexFile;
	private final PluginFingerprint pluginFingerprint;
	private final Map<Path, Instant> fileToLastModifiedTime;
	private final Path projectDir;

	private boolean updated;

	private FileIndex(Path indexFile, PluginFingerprint pluginFingerprint, Map<Path, Instant> fileToLastModifiedTime, Path projectDir) {
		this.indexFile = indexFile;
		this.pluginFingerprint = pluginFingerprint;
		this.fileToLastModifiedTime = fileToLastModifiedTime;
		this.projectDir = projectDir;
	}

	static FileIndex read(FileIndexConfig config, Log log) {
		Path indexFile = config.getIndexFile();
		if (Files.notExists(indexFile)) {
			log.info("Index file does not exist. Fallback to an empty index");
			return emptyIndex(config);
		}

		try (BufferedReader reader = newBufferedReader(indexFile, UTF_8)) {
			PluginFingerprint actualPluginFingerprint = PluginFingerprint.from(reader.readLine());
			if (!config.getPluginFingerprint().equals(actualPluginFingerprint)) {
				log.info("Fingerprint mismatch in the index file. Fallback to an empty index");
				return emptyIndex(config);
			} else {
				Map<Path, Instant> fileToLastModifiedTime = readFileToLastModifiedTime(reader, config.getProjectDir(), log);
				return new FileIndex(indexFile, config.getPluginFingerprint(), fileToLastModifiedTime, config.getProjectDir());
			}
		} catch (IOException e) {
			log.warn("Error reading the index file. Fallback to an empty index", e);
			return emptyIndex(config);
		}
	}

	Optional<Instant> getLastModifiedTime(Path file) {
		if (!file.startsWith(projectDir)) {
			return Optional.empty();
		}
		Path relativeFile = projectDir.relativize(file);
		return Optional.ofNullable(fileToLastModifiedTime.get(relativeFile));
	}

	void setLastModifiedTime(Path file, Instant time) {
		Path relativeFile = projectDir.relativize(file);
		fileToLastModifiedTime.put(relativeFile, time);
		updated = true;
	}

	void write() {
		if (!updated) {
			return;
		}

		try {
			Files.createDirectories(indexFile.getParent());
		} catch (IOException e) {
			throw new UncheckedIOException("Unable to create parent directory for the index file: " + indexFile, e);
		}
		try (PrintWriter writer = new PrintWriter(newBufferedWriter(indexFile, UTF_8, CREATE, TRUNCATE_EXISTING))) {
			writer.println(pluginFingerprint.value());

			for (Entry<Path, Instant> entry : fileToLastModifiedTime.entrySet()) {
				writer.print(entry.getKey() + SEPARATOR + entry.getValue());
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Unable to write the index", e);
		}
	}

	private static Map<Path, Instant> readFileToLastModifiedTime(BufferedReader reader, Path projectDir, Log log) throws IOException {
		Map<Path, Instant> fileToLastModifiedTime = new TreeMap<>();
		String line;
		while ((line = reader.readLine()) != null) {
			int separatorIndex = line.lastIndexOf(SEPARATOR);
			if (separatorIndex == -1) {
				throw new IOException("Incorrect index file. No separator found in '" + line + "'");
			}

			Path relativeFile = Paths.get(line.substring(0, separatorIndex));
			Path absoluteFile = projectDir.resolve(relativeFile);
			if (Files.notExists(absoluteFile)) {
				log.info("File stored in the index does not exist: " + relativeFile);
			} else {
				Instant lastModifiedTime;
				try {
					lastModifiedTime = Instant.parse(line.substring(separatorIndex));
				} catch (DateTimeParseException e) {
					throw new IOException("Incorrect index file. Unable to parse last modified time from '" + line + "'", e);
				}
				fileToLastModifiedTime.put(relativeFile, lastModifiedTime);
			}
		}
		return fileToLastModifiedTime;
	}

	private static FileIndex emptyIndex(FileIndexConfig config) {
		return new FileIndex(config.getIndexFile(), config.getPluginFingerprint(), new TreeMap<>(), config.getProjectDir());
	}
}
