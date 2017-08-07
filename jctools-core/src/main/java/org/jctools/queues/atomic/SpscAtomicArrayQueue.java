/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jctools.queues.atomic;

import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceArray;

import org.jctools.queues.IndexedQueueSizeUtil;
import org.jctools.queues.IndexedQueueSizeUtil.IndexedQueue;
import org.jctools.queues.QueueProgressIndicators;

/**
 * A Single-Producer-Single-Consumer queue backed by a pre-allocated buffer.
 * <p>
 * This implementation is a mashup of the <a href="http://sourceforge.net/projects/mc-fastflow/">Fast Flow</a>
 * algorithm with an optimization of the offer method taken from the <a
 * href="http://staff.ustc.edu.cn/~bhua/publications/IJPP_draft.pdf">BQueue</a> algorithm (a variation on Fast
 * Flow), and adjusted to comply with Queue.offer semantics with regards to capacity.<br>
 * For convenience the relevant papers are available in the resources folder:<br>
 * <i>2010 - Pisa - SPSC Queues on Shared Cache Multi-Core Systems.pdf<br>
 * 2012 - Junchang- BQueue- Efﬁcient and Practical Queuing.pdf <br>
 * </i> This implementation is wait free.
 *
 * @author akarnokd
 *
 * @param <E>
 */
public final class SpscAtomicArrayQueue<E> extends AtomicReferenceArrayQueue<E> implements IndexedQueue, QueueProgressIndicators {
    private static final AtomicLongFieldUpdater<SpscAtomicArrayQueue> P_INDEX_UPDATER = AtomicLongFieldUpdater
            .newUpdater(SpscAtomicArrayQueue.class, "producerIndex");
    private static final AtomicLongFieldUpdater<SpscAtomicArrayQueue> C_INDEX_UPDATER = AtomicLongFieldUpdater
            .newUpdater(SpscAtomicArrayQueue.class, "consumerIndex");
    
    private static final Integer MAX_LOOK_AHEAD_STEP = Integer.getInteger("jctools.spsc.max.lookahead.step", 4096);
    volatile long producerIndex;
    protected long producerLimit;
    volatile long consumerIndex;
    final int lookAheadStep;
    
    
    public SpscAtomicArrayQueue(int capacity) {
        super(Math.max(capacity, 4));
        lookAheadStep = Math.min(capacity() / 4, MAX_LOOK_AHEAD_STEP);
    }

    @Override
    public boolean offer(final E e) {
        if (null == e) {
            throw new NullPointerException();
        }
        // local load of field to avoid repeated loads after volatile reads
        final AtomicReferenceArray<E> buffer = this.buffer;
        final int mask = this.mask;
        final long producerIndex = this.producerIndex;

        if (producerIndex >= producerLimit &&
                !offerSlowPath(buffer, mask, producerIndex)) {
            return false;
        }
        final int offset = calcElementOffset(producerIndex, mask);

        soElement(buffer, offset, e); // StoreStore
        soProducerIndex(producerIndex + 1); // ordered store -> atomic and ordered for size()
        return true;
    }

    private boolean offerSlowPath(final AtomicReferenceArray<E> buffer, final int mask, final long producerIndex) {
        final int lookAheadStep = this.lookAheadStep;
        if (null == lvElement(buffer, calcElementOffset(producerIndex + lookAheadStep, mask))) {// LoadLoad
            producerLimit = producerIndex + lookAheadStep;
        }
        else {
            final int offset = calcElementOffset(producerIndex, mask);
            if (null != lvElement(buffer, offset)){
                return false;
            }
        }
        return true;
    }

    @Override
    public E poll() {
        final long consumerIndex = this.consumerIndex;
        final int offset = calcElementOffset(consumerIndex);
        // local load of field to avoid repeated loads after volatile reads
        final AtomicReferenceArray<E> buffer = this.buffer;
        final E e = lvElement(buffer, offset);// LoadLoad
        if (null == e) {
            return null;
        }
        soElement(buffer, offset, null);// StoreStore
        soConsumerIndex(consumerIndex + 1); // ordered store -> atomic and ordered for size()
        return e;
    }

    @Override
    public E peek() {
        return lvElement(buffer, calcElementOffset(consumerIndex));
    }

    private void soProducerIndex(long newIndex) {
        P_INDEX_UPDATER.lazySet(this, newIndex);
    }

    private void soConsumerIndex(long newIndex) {
        C_INDEX_UPDATER.lazySet(this, newIndex);
    }
    
    @Override
    public long lvProducerIndex() {
        return producerIndex;
    }

    @Override
    public long lvConsumerIndex() {
        return consumerIndex;
    }

    @Override
    public int size() {
        return IndexedQueueSizeUtil.size(this);
    }

    @Override
    public boolean isEmpty() {
        return IndexedQueueSizeUtil.isEmpty(this);
    }
    
    @Override
    public long currentProducerIndex() {
        return lvProducerIndex();
    }

    @Override
    public long currentConsumerIndex() {
        return lvConsumerIndex();
    }
}
