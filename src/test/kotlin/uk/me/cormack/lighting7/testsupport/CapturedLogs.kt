package uk.me.cormack.lighting7.testsupport

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/** The formatted messages [loggerName] logs while [block] runs, whatever thread logs them. */
fun <T> captureLogs(loggerName: String, block: () -> T): Pair<T, List<String>> {
    val logger = LoggerFactory.getLogger(loggerName) as Logger
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    logger.addAppender(appender)
    try {
        val result = block()
        return result to synchronized(appender.list) { appender.list.map { it.formattedMessage } }
    } finally {
        logger.detachAppender(appender)
        appender.stop()
    }
}
