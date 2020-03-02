package no.nav.helse

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths


fun getFileAsStringUTF8(filePath: String) = String(Files.readAllBytes(Paths.get(filePath)), StandardCharsets.UTF_8)
fun getFileAsStringISO88591(filePath: String) = String(Files.readAllBytes(Paths.get(filePath)), StandardCharsets.ISO_8859_1)
