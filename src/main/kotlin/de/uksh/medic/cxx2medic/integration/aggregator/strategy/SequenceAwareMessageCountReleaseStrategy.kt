package de.uksh.medic.cxx2medic.integration.aggregator.strategy

import org.springframework.integration.IntegrationMessageHeaderAccessor
import org.springframework.integration.StaticMessageHeaderAccessor
import org.springframework.integration.aggregator.MessageSequenceComparator
import org.springframework.integration.aggregator.ReleaseStrategy
import org.springframework.integration.store.MessageGroup
import org.springframework.messaging.Message
import org.springframework.messaging.support.MessageHeaderAccessor
import java.util.Collections

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
                val minMessage = Collections.min(group.messages, comparator)
                val missingMessagesInGroup =
                    group.sequenceSize - StaticMessageHeaderAccessor.getSequenceNumber(minMessage)
                missingMessagesInGroup == 0
            }
        }
    }
}