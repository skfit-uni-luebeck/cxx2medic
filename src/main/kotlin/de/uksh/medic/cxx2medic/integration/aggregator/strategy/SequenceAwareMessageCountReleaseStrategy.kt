package de.uksh.medic.cxx2medic.integration.aggregator.strategy

import org.springframework.integration.IntegrationMessageHeaderAccessor
import org.springframework.integration.StaticMessageHeaderAccessor
import org.springframework.integration.aggregator.MessageSequenceComparator
import org.springframework.integration.aggregator.ReleaseStrategy
import org.springframework.integration.store.MessageGroup
import org.springframework.messaging.Message
import org.springframework.messaging.support.MessageHeaderAccessor
import java.util.Collections

/**
 * ONLY WORKS WHEN MESSAGES ARE RECEIVED IN ORDER
 */
class SequenceAwareMessageCountReleaseStrategy(
    private val threshold: Int
): ReleaseStrategy
{
    private val comparator: Comparator<Message<*>> = MessageSequenceComparator()

    override fun canRelease(group: MessageGroup): Boolean {
        return when (val size = group.size()) {
            0           -> true
            threshold   -> true
            else        -> {
                val maxMessage = Collections.min(group.messages, comparator)
                val maxMessageSeqNumber = StaticMessageHeaderAccessor.getSequenceNumber(maxMessage)
                group.sequenceSize == maxMessageSeqNumber
            }
        }
    }
}