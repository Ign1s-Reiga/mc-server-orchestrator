package mcorch.core.testing

import java.io.File
import java.nio.file.Path

/**
 * Kotlin source read as *code*, for the wiring tests that assert on it.
 *
 * ## Why this is shared rather than copied
 *
 * Two modules pin wiring by scanning their own sources — `:core` for who may name
 * the CRI stop deadline, `:app` for which startup steps report a misconfiguration
 * the way the others do. Both need the same thing first: the file with its comments
 * gone, because **a scan that a comment can move is not a scan.** The `:app` one was
 * written without it and the forty-first audit found the asymmetry: a comment
 * containing the literal it counts would inflate one side of the count and could
 * hide a startup step genuinely outside the channel.
 *
 * Copying forty lines would have been the third copy of a thing whose *bugs* are the
 * interesting part — the depth check below exists because of one, and a copy would
 * not have inherited it. Test fixtures are already how this repo shares across
 * module test boundaries (`:schema` publishes its example definitions the same way,
 * and `:api` and `:store` consume them), so the sharing is declared rather than
 * arranged behind a relative path. It puts nothing on any main compile classpath.
 *
 * It lives in `:core` because that is where the first scan and the whole written
 * rationale for it are, and because `:app` already depends on `:core`. It is
 * deliberately **not** in `:schema`, whose fixtures are domain data; a Kotlin text
 * utility there would be a second unrelated reason for that variant to exist.
 */
public object KotlinSource {
    /**
     * A single-line string literal, blanked before any comment scanning so that
     * neither a `//` nor a comment opener inside one can open or close anything.
     *
     * Multi-line raw strings are *not* covered, which is the known hole and the
     * reason [codeLines] reports its depth — see there.
     */
    private val STRING_LITERAL = Regex(""""([^"\\]|\\.)*"""")

    /**
     * [lines] with comments removed and string literals blanked, positions
     * preserved, paired with the block-comment depth left over at the end.
     *
     * Deliberately not a Kotlin lexer. It handles what this repo actually writes —
     * block comments, KDoc included, and `//` tails — and counts nesting, because
     * Kotlin's `DelimitedComment` is recursive.
     *
     * **The depth is returned because the stripper fails open.** An unmatched opener
     * leaves every later line inside a comment, so a scan over the rest of the file
     * finds nothing and reports green. Callers must require zero;
     * [readCodeLines] does it for them.
     *
     * The reachable case is narrower than it looks, and knowing which one it is
     * matters when red-proving a caller. An opener in **prose** does not compile —
     * the nesting means a KDoc's own terminator closes the inner comment and the
     * file runs on — so that case is loud. What compiles and is silent is a
     * **multi-line raw string** containing an opener, which [STRING_LITERAL] does
     * not blank.
     */
    public fun codeLines(lines: List<String>): Pair<List<String>, Int> {
        var depth = 0
        val stripped =
            lines.map { raw ->
                val line = raw.replace(STRING_LITERAL, "\"\"")
                val kept = StringBuilder()
                var i = 0
                while (i < line.length) {
                    when {
                        line.startsWith("/*", i) -> {
                            depth++
                            i += 2
                        }

                        depth > 0 && line.startsWith("*/", i) -> {
                            depth--
                            i += 2
                        }

                        depth > 0 -> {
                            i++
                        }

                        line.startsWith("//", i) -> {
                            i = line.length
                        }

                        else -> {
                            kept.append(line[i])
                            i++
                        }
                    }
                }
                kept.toString()
            }
        return stripped to depth
    }

    /**
     * [file]'s code lines, refusing a file the stripper could not get back out of.
     *
     * @throws IllegalStateException when the file ends inside a block comment, which
     *   means every scan over it is silently vacuous.
     */
    public fun readCodeLines(file: File): List<String> {
        val (code, depth) = codeLines(file.readLines())
        check(depth == 0) {
            "${file.invariantSeparatorsPath} ends inside a block comment (depth $depth). The stripper has " +
                "blanked the rest of the file, so every scan over it is silently vacuous — look for an " +
                "unmatched opener inside a multi-line raw string, which is the spelling that compiles"
        }
        return code
    }

    /** As [readCodeLines], for a path relative to the module directory. */
    public fun readCodeLines(path: String): List<String> {
        val file = Path.of(path).toFile()
        check(file.isFile) { "expected to run with the module directory as the working directory; no $path" }
        return readCodeLines(file)
    }

    /**
     * Every `.kt` under [root] as `path to its code lines`, in path order.
     *
     * Test sources are deliberately left to the caller to exclude or include: a
     * fake, a harness and a guard test legitimately do things a production path may
     * not, and folding them in silently would make a scan an exception list.
     */
    public fun tree(root: String): List<Pair<String, List<String>>> =
        Path
            .of(root)
            .toFile()
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.invariantSeparatorsPath to readCodeLines(it) }
            .sortedBy { it.first }
            .toList()
}
