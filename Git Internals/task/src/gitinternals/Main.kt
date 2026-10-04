package gitinternals

import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.InflaterInputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// --- IO Handlers ---

class GitObjectLocator(private val gitDir: String, private val hash: String) {
    fun getFile(): File {
        require(hash.length == 40) { "Invalid Git hash length." }
        val dirPrefix = hash.substring(0, 2)
        val fileName = hash.substring(2)
        return File(gitDir, "objects${File.separator}$dirPrefix${File.separator}$fileName")
    }
}

class ZlibFileReader(private val file: File) {
    fun readAndDecompress(): ByteArray {
        require(file.exists()) { "Git object not found at: ${file.absolutePath}" }
        return InflaterInputStream(file.inputStream()).use { inflater ->
            val outputStream = ByteArrayOutputStream()
            inflater.copyTo(outputStream)
            outputStream.toByteArray()
        }
    }
}

// --- Domain Models ---

abstract class GitObject {
    abstract fun displayFormatted()
}

class BlobObject(private val content: String) : GitObject() {
    override fun displayFormatted() {
        println("*BLOB*")
        print(content)
    }
}

class CommitObject(content: String) : GitObject() {
    val tree: String
    val parents = mutableListOf<String>()
    val authorLine: String
    val committerLine: String
    val message: String

    init {
        val doubleNewlineIdx = content.indexOf("\n\n")
        val metadata = if (doubleNewlineIdx != -1) content.substring(0, doubleNewlineIdx) else content
        message = if (doubleNewlineIdx != -1) content.substring(doubleNewlineIdx + 2) else ""

        val lines = metadata.split("\n")
        var t = ""
        var aLine = ""
        var cLine = ""

        for (line in lines) {
            when {
                line.startsWith("tree ") -> t = line.substringAfter("tree ")
                line.startsWith("parent ") -> parents.add(line.substringAfter("parent "))
                line.startsWith("author ") -> aLine = line.substringAfter("author ")
                line.startsWith("committer ") -> cLine = line.substringAfter("committer ")
            }
        }
        tree = t
        authorLine = aLine
        committerLine = cLine
    }

    override fun displayFormatted() {
        println("*COMMIT*")
        println("tree: $tree")
        if (parents.isNotEmpty()) {
            println("parents: ${parents.joinToString(" | ")}")
        }
        println("author: ${formatUserLine(authorLine, "original timestamp")}")
        println("committer: ${getFormattedCommitter()}")
        println("commit message:")
        print(message)
    }

    fun getFormattedCommitter(): String {
        return formatUserLine(committerLine, "commit timestamp")
    }

    private fun formatUserLine(line: String, timestampLabel: String): String {
        val parts = line.split(" ")
        val name = parts[0]
        val email = parts[1].replace("<", "").replace(">", "")
        val timestamp = parts[2].toLong()
        val timezone = parts[3]

        val instant = Instant.ofEpochSecond(timestamp)
        val zone = ZoneOffset.of(timezone)
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX")
        val formattedDate = formatter.format(instant.atZone(zone))

        return "$name $email $timestampLabel: $formattedDate"
    }
}

data class TreeEntry(val mode: String, val hash: String, val filename: String)

class TreeObject(contentBytes: ByteArray) : GitObject() {
    val entries = mutableListOf<TreeEntry>()

    init {
        var i = 0
        while (i < contentBytes.size) {
            val modeStart = i
            while (i < contentBytes.size && contentBytes[i] != ' '.code.toByte()) i++
            val mode = String(contentBytes.copyOfRange(modeStart, i), Charsets.UTF_8)
            i++

            val fileStart = i
            while (i < contentBytes.size && contentBytes[i] != 0.toByte()) i++
            val filename = String(contentBytes.copyOfRange(fileStart, i), Charsets.UTF_8)
            i++

            val sha1Bytes = contentBytes.copyOfRange(i, i + 20)
            i += 20

            val hexSha1 = sha1Bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            entries.add(TreeEntry(mode, hexSha1, filename))
        }
    }

    override fun displayFormatted() {
        println("*TREE*")
        for (entry in entries) {
            println("${entry.mode} ${entry.hash} ${entry.filename}")
        }
    }
}

object GitObjectFactory {
    fun create(rawBytes: ByteArray): GitObject {
        val nullIndex = rawBytes.indexOf(0.toByte())
        if (nullIndex == -1) throw IllegalArgumentException("Invalid Git object")

        val header = String(rawBytes.copyOfRange(0, nullIndex), Charsets.UTF_8)
        val type = header.substringBefore(" ")
        val contentBytes = rawBytes.copyOfRange(nullIndex + 1, rawBytes.size)

        return when (type) {
            "blob" -> BlobObject(String(contentBytes, Charsets.UTF_8))
            "commit" -> CommitObject(String(contentBytes, Charsets.UTF_8))
            "tree" -> TreeObject(contentBytes)
            else -> throw IllegalArgumentException("Unsupported object type: $type")
        }
    }
}

// --- Managers ---

class GitBranchManager(private val gitDir: String) {
    fun listBranches() {
        val headFile = File(gitDir, "HEAD")
        val headsDir = File(gitDir, "refs${File.separator}heads")

        var currentBranch = ""
        if (headFile.exists()) {
            val headText = headFile.readText().trim()
            if (headText.startsWith("ref: refs/heads/")) {
                currentBranch = headText.removePrefix("ref: refs/heads/")
            }
        }

        if (headsDir.exists() && headsDir.isDirectory) {
            val branches = headsDir.walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(headsDir).path.replace("\\", "/") }
                .sorted()
                .toList()

            for (branch in branches) {
                if (branch == currentBranch) println("* $branch")
                else println("  $branch")
            }
        }
    }
}

class GitTreeManager(private val gitDir: String) {

    fun printCommitTree(commitHash: String) {
        val rawBytes = ZlibFileReader(GitObjectLocator(gitDir, commitHash).getFile()).readAndDecompress()
        val commitObj = GitObjectFactory.create(rawBytes) as CommitObject

        // Start the recursive traversal from the root tree of the commit
        traverseTree(commitObj.tree, "")
    }

    private fun traverseTree(treeHash: String, currentPath: String) {
        val rawBytes = ZlibFileReader(GitObjectLocator(gitDir, treeHash).getFile()).readAndDecompress()
        val treeObj = GitObjectFactory.create(rawBytes) as TreeObject

        for (entry in treeObj.entries) {
            // Build the path natively mimicking Git's forward-slash format
            val fullPath = if (currentPath.isEmpty()) entry.filename else "$currentPath/${entry.filename}"

            // Git tree modes for directories are "40000" or "040000". Blobs are typically "100644"
            if (entry.mode == "40000" || entry.mode == "040000") {
                // If it's a directory, dive deeper (Recursion)
                traverseTree(entry.hash, fullPath)
            } else {
                // If it's a file, print the full path
                println(fullPath)
            }
        }
    }
}

// --- Main Application Router ---

fun main() {
    println("Enter .git directory location:")
    val gitDir = readlnOrNull() ?: return

    println("Enter command:")
    val command = readlnOrNull() ?: return

    try {
        when (command) {
            "list-branches" -> {
                GitBranchManager(gitDir).listBranches()
            }
            "cat-file" -> {
                println("Enter git object hash:")
                val hash = readlnOrNull() ?: return

                val rawBytes = ZlibFileReader(GitObjectLocator(gitDir, hash).getFile()).readAndDecompress()
                val gitObject = GitObjectFactory.create(rawBytes)
                gitObject.displayFormatted()
            }
            "log" -> {
                println("Enter branch name:")
                val branch = readlnOrNull() ?: return

                val branchFile = File(gitDir, "refs${File.separator}heads${File.separator}$branch")
                if (!branchFile.exists()) return

                var currentHash: String? = branchFile.readText().trim()
                var isFirst = true

                while (currentHash != null) {
                    val rawBytes = ZlibFileReader(GitObjectLocator(gitDir, currentHash).getFile()).readAndDecompress()
                    val commitObj = GitObjectFactory.create(rawBytes) as CommitObject

                    if (!isFirst) println()
                    isFirst = false

                    println("Commit: $currentHash")
                    println(commitObj.getFormattedCommitter())
                    println(commitObj.message.trimEnd())

                    if (commitObj.parents.size == 2) {
                        val mergedHash = commitObj.parents[1]
                        val mergedBytes = ZlibFileReader(GitObjectLocator(gitDir, mergedHash).getFile()).readAndDecompress()
                        val mergedObj = GitObjectFactory.create(mergedBytes) as CommitObject

                        println()
                        println("Commit: $mergedHash (merged)")
                        println(mergedObj.getFormattedCommitter())
                        println(mergedObj.message.trimEnd())
                    }

                    currentHash = if (commitObj.parents.isNotEmpty()) commitObj.parents[0] else null
                }
            }
            "commit-tree" -> {
                println("Enter commit-hash:")
                val hash = readlnOrNull() ?: return

                GitTreeManager(gitDir).printCommitTree(hash)
            }
            else -> println("Unknown command: $command")
        }
    } catch (e: Exception) {
        println("Error: ${e.message}")
    }
}