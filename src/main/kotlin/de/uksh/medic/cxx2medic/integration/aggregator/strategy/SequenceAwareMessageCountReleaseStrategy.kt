package de.uksh.medic.cxx2medic.integration.aggregator.strategy

import org.springframework.integration.aggregator.MessageSequenceComparator
import org.springframework.integration.aggregator.ReleaseStrategy
import org.springframework.integration.store.MessageGroup
import org.springframework.messaging.Message

class SequenceAwareMessageCountReleaseStrategy(
    private val threshold: Int
): ReleaseStrategy
{
    private val comparator: Comparator<Message<*>> = MessageSequenceComparator()

    override fun canRelease(group: MessageGroup): Boolean {
        val remainingMessagesInGroup = group.sequenceSize - group.lastReleasedMessageSequenceNumber
        return when (val size = group.size()) {
            0           -> true
            threshold   -> true
            else        -> size == remainingMessagesInGroup
        }
    }
}