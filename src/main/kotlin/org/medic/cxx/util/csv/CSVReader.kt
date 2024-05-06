package org.medic.cxx.util.csv

import org.medic.cxx.exception.UnsupportedValueException
import org.medic.cxx.util.iteration.EscapeStrategy
import java.io.BufferedReader
import java.nio.file.Path
import org.medic.cxx.util.iteration.EscapeStrategy.*
import org.medic.cxx.util.iteration.Finite
import org.medic.cxx.util.iteration.ResponsibleIterator
import java.io.Closeable
import java.util.*
import java.util.function.Consumer
import java.util.regex.Pattern
import java.util.stream.Stream
import java.util.stream.StreamSupport
import kotlin.NoSuchElementException

class CSVReader(
    path: Path,
    private val separator: Char = ',',
    private val quote: Char = '"',
    private val escapeStrategy: EscapeStrategy = BACKSLASH,
    val hasHeader: Boolean = true
): Iterable<Array<String>>, Finite, Closeable
{

    companion object
    {

        private fun splitLine(line: String, separator: Char, quote: Char, escapeStrategy: EscapeStrategy): Array<String>
        {
            val pattern = buildRegex(separator, quote, escapeStrategy)
            return pattern.split(line)
        }

        private fun buildRegex(separator: Char, quote: Char, escapeStrategy: EscapeStrategy): Pattern
        {
            if (escapeStrategy != BACKSLASH)
                throw UnsupportedValueException("Unsupported escape strategy: ${escapeStrategy.name}")
            val quoteStr = if (quote == '"') "\\\"" else "$quote"
            val template = "(?x)${separator}(?=(?:[^$quoteStr]*$quoteStr[^$quoteStr]*$quoteStr)*[^$quoteStr]*\$)"
            return Pattern.compile(template)
        }

    }

    private val reader: BufferedReader = path.toFile().bufferedReader()
    private var nextLine: Array<String>? = null
    private val header: Array<String>?

    init
    {
        // Init header
        if (hasHeader) {
            this.header = getNextLine() ?: throw UnsupportedValueException("Error reading header line")
        }
        else {
            this.header = null
        }

        // Init first data row
        this.nextLine = getNextLine()
    }

    override fun iterator(): ResponsibleIterator<Array<String>, CSVReader> = Iterator(this)

    override fun forEach(action: Consumer<in Array<String>>?) {
        var line = this.readLine()
        while (!this.hasEnded()) {
            action?.accept(line)
            line = this.readLine()
        }
    }

    override fun hasEnded(): Boolean = this.nextLine == null

    override fun close() = this.reader.close()

    fun reset() = this.reader.reset()

    fun readLine(): Array<String>
    {
        val line = this.nextLine
        if (line == null) throw NoSuchElementException()
        else {
            this.nextLine = getNextLine()
            return line
        }
    }

    fun lines(parallel: Boolean = false): Stream<Array<String>>
    {
        return StreamSupport.stream(
            Spliterators.spliteratorUnknownSize(this.iterator(), Spliterator.ORDERED or Spliterator.NONNULL),
            parallel
        )
    }

    private fun getNextLine(): Array<String>?
    {
        val line = this.reader.readLine() ?: return null
        return splitLine(line, this.separator, this.quote, this.escapeStrategy)
    }

    class Iterator(private val reader: CSVReader): ResponsibleIterator<Array<String>, CSVReader>(reader)
    {

        private val header: Array<String>? = this.reader.header

        override fun next(): Array<String> = this.reader.readLine()

    }

}

fun iterateByLine(path: Path): Iterator<Array<String>> = CSVReader(path).iterator()

fun streamByLine(path: Path): Stream<Array<String>> = CSVReader(path).lines()